package com.polyethene.polyhouse.service;

import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.config.TrendAiProperties;
import com.polyethene.polyhouse.mapper.TrendAiMapper;
import com.polyethene.polyhouse.mapper.TrendBriefingMapper;
import com.polyethene.polyhouse.model.entity.TrendBriefingRecord;
import com.polyethene.polyhouse.model.entity.TrendBriefingRequest;
import com.polyethene.polyhouse.service.trend.TrendBriefingCodec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.Objects;

/**
 * 7 日风口简报持久账本：先预留后调用，与单仓账本共享 tool_trend_ai_month 十元月预算与熔断开关；
 * 严格遵循 简报槽(anchor_date) -> 月预算(month_key) -> 简报请求(id) 锁序，绝不与单仓锁序产生死锁。
 */
@Service
public class TrendBriefingLedger {
    private final TrendBriefingMapper mapper;
    private final TrendAiMapper aiMapper;
    private final TrendAiProperties settings;
    private final Clock clock;
    private final TransactionTemplate transaction;

    /** 复用共享月预算映射与统一 UTC 时钟，不另建独立预算开关。 */
    public TrendBriefingLedger(TrendBriefingMapper mapper, TrendAiMapper aiMapper,
                               TrendAiProperties settings, Clock clock, PlatformTransactionManager manager) {
        this.mapper = mapper;
        this.aiMapper = aiMapper;
        this.settings = settings;
        this.clock = clock;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setTimeout(5);
    }

    /** 只读查询锚点日最新有效简报，不触碰预算表或活动槽位。 */
    public TrendBriefingRecord briefing(LocalDate anchorDate) {
        return mapper.briefing(anchorDate);
    }

    /** 供费用服务判断待核对请求是否归属简报账本。 */
    public boolean hasRequest(String requestId) {
        return requestId != null && mapper.request(requestId) != null;
    }

    /**
     * 预留单次简报生成预算并占用锚点日执行槽；同指纹且同模型规则已有有效稿时零费用返回 REUSED。
     * @param id 本次请求幂等 UUID
     * @param anchorDate 观察窗口锚点日期（北京时间）
     * @param fingerprint 本次输入快照的 64 位 SHA-256 指纹
     * @param inputBound 保守输入 token 上界
     * @param outputBound 本次最大输出 token 上界
     * @param expectedFingerprint 调用方预检查时看到的已有有效稿指纹（无旧稿时为 null），变化时返回 RECHECK
     * @return 预留决策及锁内看到的最新简报记录
     */
    public Decision reserve(String id, LocalDate anchorDate, String fingerprint, int inputBound,
                            int outputBound, String expectedFingerprint) {
        if (!settings.ready() || anchorDate == null || id == null
                || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || fingerprint == null || !fingerprint.matches("[a-f0-9]{64}")
                || inputBound <= 0 || inputBound > 32_000
                || outputBound <= 0 || outputBound > settings.maxOutputTokens()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER);
        }
        long amount = TrendAiLedger.cost(inputBound, outputBound, settings.inputYuanPerMillion(), settings.outputYuanPerMillion());
        Instant now = clock.instant();
        String month = YearMonth.from(now.atZone(ZoneId.of("Asia/Shanghai"))).toString();
        return transaction.execute(status -> {
            mapper.ensureBriefing(anchorDate);
            TrendBriefingRecord slot = mapper.lockBriefing(anchorDate);
            if (mapper.request(id) != null) return new Decision("REPLAY", null, slot);
            if (slot.activeRequest() != null) return new Decision("PENDING", null, slot);
            if (slot.payload() != null && fingerprint.equals(slot.sourceFingerprint())
                    && settings.model().equals(slot.model())
                    && TrendBriefingCodec.RULE.equals(slot.ruleVersion())) {
                return new Decision("REUSED", null, slot);
            }
            if (!Objects.equals(expectedFingerprint, slot.sourceFingerprint())) {
                return new Decision("RECHECK", null, slot);
            }
            aiMapper.ensureMonth(month);
            aiMapper.lockMonth(month);
            if (amount > settings.monthlyBudgetMicros() || aiMapper.hold(month, amount, settings.monthlyBudgetMicros()) != 1) {
                return new Decision("BUDGET_LIMIT", null, slot);
            }
            var request = new TrendBriefingRequest(id, month, anchorDate, fingerprint, settings.model(),
                    TrendBriefingCodec.RULE, "RESERVED", amount, null, inputBound, outputBound,
                    settings.inputYuanPerMillion(), settings.outputYuanPerMillion());
            if (mapper.insert(request, LocalDateTime.ofInstant(now, ZoneOffset.UTC)) != 1
                    || mapper.claim(anchorDate, id) != 1) {
                throw conflict();
            }
            return new Decision("GRANTED", request, slot);
        });
    }

    /** 已知用量时结算费用；validPayload 为 null 表示模型输出未通过校验，此时保留原有效旧稿但照实扣费。 */
    public void settle(String id, int input, int output, SettlementPayload validPayload) {
        complete(id, input, output, validPayload, false);
    }

    /** 仅限确认请求尚未发出时的本地失败退回预留，绝不可用于网络超时。 */
    public void cancelBeforeSend(String id) {
        complete(id, 0, 0, null, true);
    }

    /** 超时或用量越界保持 UNKNOWN 且不退预留；用量违约即使已人工关闭也必须暂停原月预算。 */
    public void unknown(String id, boolean usageExceeded) {
        transaction.executeWithoutResult(status -> {
            var request = mapper.request(id);
            if (request == null) throw conflict();
            mapper.lockBriefing(request.anchorDate());
            aiMapper.lockMonth(request.monthKey());
            var current = mapper.lockRequest(id);
            if ("RESERVED".equals(current.state())) {
                mapper.unknown(id, usageExceeded ? "USAGE_OUT_OF_BOUNDS" : "RESULT_UNKNOWN");
            }
            if (usageExceeded && !"CANCELLED".equals(current.state())) {
                aiMapper.suspend(current.monthKey());
            }
        });
    }

    /**
     * 仅在屋主费用处理事务持有空闲任务槽时调用；按原预留全额计入原月预算并释放简报执行槽，保留原有效旧稿。
     * @param id 待核对的简报请求 UUID
     * @param expected 屋主明确确认的原预留微元数
     * @param now 持有任务槽时读取的数据库 UTC 时间
     * @return 供同事务写入审计表的结算事实
     */
    public ConservativeCharge closeConservatively(String id, long expected, LocalDateTime now) {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || expected < 1 || expected > 10_000_000 || now == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER);
        }
        return transaction.execute(status -> {
            var request = mapper.request(id);
            if (request == null) throw new BusinessException(ErrorCode.NOT_FOUND);
            var slot = mapper.lockBriefing(request.anchorDate());
            aiMapper.lockMonth(request.monthKey());
            var current = mapper.lockRequest(id);
            if (current == null || !("RESERVED".equals(current.state()) || "UNKNOWN".equals(current.state()))
                    || slot == null || !id.equals(slot.activeRequest()) || current.reservedMicros() != expected) {
                throw conflict();
            }
            var created = mapper.createdAt(id);
            if (created == null || now.isBefore(created.plusMinutes(5))) {
                throw new BusinessException(ErrorCode.CONFLICT, "请等待原请求预留至少五分钟后再处理");
            }
            if (aiMapper.settle(current.monthKey(), expected, expected) != 1
                    || mapper.closeReserved(id, expected) != 1
                    || mapper.releaseWithoutPayload(current.anchorDate(), id) != 1) {
                throw conflict();
            }
            return new ConservativeCharge(id, current.monthKey(), current.anchorDate(), expected);
        });
    }

    private void complete(String id, int input, int output, SettlementPayload validPayload, boolean cancelled) {
        transaction.executeWithoutResult(status -> {
            var request = mapper.request(id);
            if (request == null) throw conflict();
            var slot = mapper.lockBriefing(request.anchorDate());
            aiMapper.lockMonth(request.monthKey());
            var current = mapper.lockRequest(id);
            if ("SETTLED".equals(current.state()) || "CANCELLED".equals(current.state())) return;
            if (cancelled && !"RESERVED".equals(current.state())) throw conflict();
            if (slot == null || !id.equals(slot.activeRequest()) || input < 0 || input > current.inputBound()
                    || output < 0 || output > current.outputBound()) {
                throw conflict();
            }
            long charged = TrendAiLedger.cost(input, output, current.inputRate(), current.outputRate());
            if (charged > current.reservedMicros()) throw conflict();
            if (validPayload != null) {
                if (validPayload.payloadJson() == null || validPayload.payloadJson().length() > 16_384
                        || validPayload.windowStart() == null || !current.anchorDate().equals(validPayload.windowEnd())
                        || validPayload.coveredDays() < 3 || validPayload.coveredDays() > 7
                        || !("LIMITED".equals(validPayload.sampleStatus()) || "SUFFICIENT".equals(validPayload.sampleStatus()))
                        || validPayload.generatedAt() == null) {
                    throw conflict();
                }
            }
            if (aiMapper.settle(current.monthKey(), current.reservedMicros(), charged) != 1
                    || mapper.finish(id, cancelled ? "CANCELLED" : "SETTLED", charged, input, output,
                    cancelled ? "NOT_SENT" : validPayload == null ? "INVALID_OUTPUT" : "VALID_OUTPUT") != 1) {
                throw conflict();
            }
            int released = validPayload == null
                    ? mapper.releaseWithoutPayload(current.anchorDate(), id)
                    : mapper.releaseWithPayload(current.anchorDate(), id, validPayload.windowStart(),
                            validPayload.windowEnd(), validPayload.coveredDays(), validPayload.sampleStatus(),
                            current.sourceFingerprint(), current.model(), current.ruleVersion(),
                            validPayload.payloadJson(), LocalDateTime.ofInstant(validPayload.generatedAt(), ZoneOffset.UTC));
            if (released != 1) throw conflict();
        });
    }

    /**
     * 预留决策，不携带密钥或 Prompt。
     * @param status 固定决策代号：GRANTED、REUSED、PENDING、REPLAY、RECHECK 或 BUDGET_LIMIT
     * @param request 仅 GRANTED 时携带已落库的预留事实
     * @param briefing 锁内看到的最新有效简报槽位记录
     */
    public record Decision(String status, TrendBriefingRequest request, TrendBriefingRecord briefing) { }

    /**
     * 校验通过后的有效简报写入载荷，随费用结算同事务原子落库。
     * @param windowStart 覆盖窗口起始日
     * @param windowEnd 覆盖窗口结束日（必须等于锚点日）
     * @param coveredDays 实际覆盖天数（3～7）
     * @param sampleStatus 样本档位（LIMITED 或 SUFFICIENT）
     * @param payloadJson 经 TrendBriefingCodec 编码的脱敏 JSON
     * @param generatedAt 生成时间（UTC）
     */
    public record SettlementPayload(LocalDate windowStart, LocalDate windowEnd, int coveredDays,
                                    String sampleStatus, String payloadJson, Instant generatedAt) { }

    /**
     * 简报请求保守结算事实，供费用服务同事务写入 tool_trend_briefing_resolution。
     * @param requestId 原简报请求 UUID
     * @param monthKey 原预留北京月份
     * @param anchorDate 释放执行槽对应的锚点日
     * @param accountedMicros 全额原预留微元
     */
    public record ConservativeCharge(String requestId, String monthKey, LocalDate anchorDate, long accountedMicros) { }

    private static BusinessException conflict() {
        return new BusinessException(ErrorCode.CONFLICT, "风口简报账本状态已变化");
    }
}
