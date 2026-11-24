package com.polyethene.polyhouse.service;

import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.config.TrendAiProperties;
import com.polyethene.polyhouse.mapper.*;
import com.polyethene.polyhouse.model.dto.*;
import com.polyethene.polyhouse.model.entity.TrendAiResolutionRecord;
import com.polyethene.polyhouse.model.vo.*;
import com.polyethene.polyhouse.service.trend.TrendOwnerAuthorization;
import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.Objects;
import java.util.function.Supplier;

/** 屋主费用只读与保守处理（统一覆盖单仓与 7 日风口简报）；审计/账本/执行槽同事务，网络与真实账单不在此服务内。 */
@Service
public class TrendFeeService {
    private final TrendOwnerAuthorization owners;
    private final TrendFeeMapper fees;
    private final TrendJobMapper jobMapper;
    private final TrendJobStore jobs;
    private final TrendAiLedger ledger;
    private final TrendBriefingLedger briefingLedger;
    private final TrendAiProperties ai;
    private final Validator validator;
    private final TransactionTemplate transaction;

    /** 与屋主任务共用事务管理器和身份复核，统一承接单仓与 7 日风口简报的待核对查询与保守结算。 */
    @Autowired
    public TrendFeeService(TrendOwnerAuthorization owners, TrendFeeMapper fees, TrendJobMapper jobMapper,
                            TrendJobStore jobs, TrendAiLedger ledger, TrendBriefingLedger briefingLedger,
                            TrendAiProperties ai, Validator validator, PlatformTransactionManager manager) {
        this.owners = owners; this.fees = fees; this.jobMapper = jobMapper; this.jobs = jobs;
        this.ledger = ledger; this.briefingLedger = briefingLedger; this.ai = ai; this.validator = validator;
        transaction = new TransactionTemplate(manager); transaction.setTimeout(5);
    }

    /** 兼容既有单仓测试的 8 参数构造；未传入简报账本时仅处理单仓请求。 */
    public TrendFeeService(TrendOwnerAuthorization owners, TrendFeeMapper fees, TrendJobMapper jobMapper,
                            TrendJobStore jobs, TrendAiLedger ledger, TrendAiProperties ai,
                            Validator validator, PlatformTransactionManager manager) {
        this(owners, fees, jobMapper, jobs, ledger, null, ai, validator, manager);
    }

    /** 关闭执行开关后仍可诊断；只读固定月份及合并后的有界待核对页，不触发AI、建月或改变预算。 */
    public TrendFeeVO query(TrendFeeQueryDTO request, String token) {
        validate(request);
        return stored(() -> transaction.execute(status -> {
            owners.lockSession(token);
            String month = request.month() == null ? YearMonth.from(jobMapper.now().toInstant(ZoneOffset.UTC)
                    .atZone(ZoneId.of("Asia/Shanghai"))).toString() : request.month();
            var budget = ai.monthlyBudgetMicros();
            if (budget == null || budget < 1 || budget > 10_000_000) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "当前预算配置无效，费用事实仍保留");
            var summary = fees.month(month);
            long accounted = summary == null ? 0 : summary.usedMicros();
            long held = summary == null ? 0 : summary.heldMicros();
            var page = fees.pending(month, request.after(), request.limit() + 1);
            var items = page.stream().limit(request.limit()).map(item -> new TrendPendingChargeVO(item.id(),
                    item.repositoryId(), item.state(), item.reservedMicros(),
                    item.createdAt().toInstant(ZoneOffset.UTC), item.chargeKind())).toList();
            return new TrendFeeVO(month, budget, accounted, held, Math.max(0, budget - accounted - held),
                    summary == null ? 0 : summary.conservativeMicros(), summary != null && summary.suspended(), items,
                    page.size() > request.limit() ? items.getLast().requestId() : null);
        }));
    }

    /**
     * 调用本身不产生平台费用；只接受精确屋主确认，把未知预留全额计入原月并追加不可变审计（自动识别单仓或简报请求）。
     * 相同意图返回原事实；相同UUID改参数、另一UUID再次关闭同笔请求均拒绝。
     */
    public TrendFeeResolutionVO resolve(TrendFeeResolveDTO request, String token) {
        validate(request);
        return stored(() -> transaction.execute(status -> {
            var actor = owners.lockSession(token);
            // 快速确认已存在事实不需要空闲槽；唯一OWNER行锁串行管理操作，审计本身不可变。
            var previous = findResolution(request.resolutionId());
            if (previous != null) return replay(previous, request, actor.getId());
            var now = jobs.lockIdleForFeeResolution();
            previous = lockExistingResolution(request.resolutionId());
            if (previous != null) return replay(previous, request, actor.getId());
            if (fees.lockByRequest(request.requestId()) != null || fees.lockBriefingByRequest(request.requestId()) != null) {
                throw conflict();
            }
            if (briefingLedger != null && briefingLedger.hasRequest(request.requestId())) {
                var charge = briefingLedger.closeConservatively(request.requestId(), request.expectedReservedMicros(), now);
                var record = new TrendAiResolutionRecord(request.resolutionId(), charge.requestId(), actor.getId(),
                        charge.monthKey(), charge.accountedMicros(), request.reason(), now);
                if (fees.insertBriefingResolution(record) != 1) throw conflict();
                return view(record, false);
            }
            var charge = ledger.closeConservatively(request.requestId(), request.expectedReservedMicros(), now);
            var record = new TrendAiResolutionRecord(request.resolutionId(), charge.requestId(), actor.getId(), charge.monthKey(),
                    charge.accountedMicros(), request.reason(), now);
            if (fees.insert(record) != 1) throw conflict();
            return view(record, false);
        }));
    }

    /** 响应丢失后查询同一处理编号（兼容单仓与简报审计）；404不证明原事务一定失败，应沿用原编号重试。 */
    public TrendFeeResolutionVO confirm(TrendFeeResolutionQueryDTO request, String token) {
        validate(request);
        return stored(() -> transaction.execute(status -> {
            owners.lockSession(token);
            var record = findResolution(request.resolutionId());
            if (record == null) throw new BusinessException(ErrorCode.NOT_FOUND);
            return view(record, true);
        }));
    }

    private TrendAiResolutionRecord findResolution(String resolutionId) {
        var record = fees.resolution(resolutionId);
        return record != null ? record : fees.briefingResolution(resolutionId);
    }

    private TrendAiResolutionRecord lockExistingResolution(String resolutionId) {
        var record = fees.lockResolution(resolutionId);
        return record != null ? record : fees.lockBriefingResolution(resolutionId);
    }

    private static TrendFeeResolutionVO replay(TrendAiResolutionRecord record, TrendFeeResolveDTO request, long actor) {
        if (record.actorId() != actor || !record.requestId().equals(request.requestId())
                || record.accountedMicros() != request.expectedReservedMicros() || !Objects.equals(record.reason(), request.reason())) throw conflict();
        return view(record, true);
    }
    private static TrendFeeResolutionVO view(TrendAiResolutionRecord record, boolean replayed) {
        return new TrendFeeResolutionVO(record.id(), record.requestId(), record.monthKey(), record.accountedMicros(),
                record.reason(), record.completedAt().toInstant(ZoneOffset.UTC), replayed);
    }
    private void validate(Object request) {
        if (request == null || !validator.validate(request).isEmpty()) throw new BusinessException(ErrorCode.INVALID_PARAMETER);
    }
    private static <T> T stored(Supplier<T> action) {
        try { return action.get(); }
        catch (DataAccessException | TransactionException failure) { throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE); }
    }
    private static BusinessException conflict() { return new BusinessException(ErrorCode.CONFLICT, "费用处理事实或确认参数已变化"); }
}
