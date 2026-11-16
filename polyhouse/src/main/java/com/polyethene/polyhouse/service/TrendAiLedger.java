package com.polyethene.polyhouse.service;

import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.config.TrendAiProperties;
import com.polyethene.polyhouse.mapper.TrendAiMapper;
import com.polyethene.polyhouse.model.entity.*;
import com.polyethene.polyhouse.service.trend.TrendInsightCodec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.Objects;

/** 持久账本先预留后调用，结算与缓存发布同事务；不负责网络或身份推断。 */
@Service
public class TrendAiLedger {
    private final TrendAiMapper mapper;
    private final TrendAiProperties settings;
    private final Clock clock;
    private final TransactionTemplate transaction;

    /** 复用现有服务端 UTC 时钟；不会访问成长业务或另建全局时钟。 */
    public TrendAiLedger(TrendAiMapper mapper, TrendAiProperties settings, Clock clock, PlatformTransactionManager manager) {
        this.mapper = mapper;
        this.settings = settings;
        this.clock = clock;
        transaction = new TransactionTemplate(manager);
        transaction.setTimeout(5);
    }

    /** 读取成功缓存不修改分析时间，陈旧与失败的回退由解读服务判断。 */
    public TrendAiRepository repository(long id) { return mapper.repository(id); }

    /**
     * 获准仅表示本次新调用；重放、已有执行、缓存被并发更新或预算不足都不能发起请求。
     * @param id 本次执行和仓库决定的 UUID
     * @param repo GitHub 稳定编号
     * @param sha 资料指纹
     * @param inputBound 完整请求字节数加固定开销的输入上界
     * @param outputBound 本次最大输出 token
     * @param expectedPayload 调用方已检查的缓存，变化时需重新读取而非重复付费
     * @return 决策以及在锁内看到的最新成功缓存
     */
    public Decision reserve(String id, long repo, String sha, int inputBound, int outputBound, String expectedPayload) {
        if (!settings.ready() || repo <= 0 || id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") || sha == null
                || !sha.matches("(?:[a-f0-9]{40}|[a-f0-9]{64})") || inputBound <= 0 || inputBound > 40_000
                || outputBound <= 0 || outputBound > 1500) throw new BusinessException(ErrorCode.INVALID_PARAMETER);
        long amount = cost(inputBound, outputBound, settings.inputYuanPerMillion(), settings.outputYuanPerMillion());
        var now = clock.instant();
        String month = YearMonth.from(now.atZone(ZoneId.of("Asia/Shanghai"))).toString();
        return transaction.execute(status -> {
            mapper.ensureRepository(repo);
            var cache = mapper.lockRepository(repo);
            if (mapper.request(id) != null) return new Decision("REPLAY", null, cache.payload());
            if (cache.activeRequest() != null) return new Decision("PENDING", null, cache.payload());
            if (!Objects.equals(expectedPayload, cache.payload())) return new Decision("RECHECK", null, cache.payload());
            mapper.ensureMonth(month);
            mapper.lockMonth(month);
            if (amount > settings.monthlyBudgetMicros() || mapper.hold(month, amount, settings.monthlyBudgetMicros()) != 1) {
                return new Decision("BUDGET_LIMIT", null, cache.payload());
            }
            var request = new TrendAiRequest(id, month, repo, sha, settings.model(), TrendInsightCodec.RULE, "RESERVED", amount, null,
                    inputBound, outputBound, settings.inputYuanPerMillion(), settings.outputYuanPerMillion());
            if (mapper.insert(request, LocalDateTime.ofInstant(now, ZoneOffset.UTC)) != 1 || mapper.claim(repo, id) != 1) throw conflict();
            return new Decision("GRANTED", request, cache.payload());
        });
    }

    /** 已知用量即使输出非法也需要结算；缓存仅在结构验证成功时更新。 */
    public void settle(String id, int input, int output, String payload) {
        complete(id, input, output, payload, false);
    }

    /** 只有能够证明尚未发送的失败可退回预留，不允许用网络超时调用此方法。 */
    public void cancelBeforeSend(String id) { complete(id, 0, 0, null, true); }

    /** 无响应/无可信用量保持未知；用量违约额外暂停该月。 */
    public void unknown(String id, boolean usageExceeded) {
        transaction.executeWithoutResult(status -> {
            var request = mapper.request(id);
            if (request == null) throw conflict();
            mapper.lockRepository(request.repositoryId());
            mapper.lockMonth(request.monthKey());
            var current = mapper.lockRequest(id);
            if ("RESERVED".equals(current.state())) {
                mapper.unknown(id, usageExceeded ? "USAGE_OUT_OF_BOUNDS" : "RESULT_UNKNOWN");
                if (usageExceeded) mapper.suspend(current.monthKey());
            }
        });
    }

    private void complete(String id, int input, int output, String payload, boolean cancelled) {
        transaction.executeWithoutResult(status -> {
            var request = mapper.request(id);
            if (request == null) throw conflict();
            var cache = mapper.lockRepository(request.repositoryId());
            mapper.lockMonth(request.monthKey());
            var current = mapper.lockRequest(id);
            if ("SETTLED".equals(current.state()) || "CANCELLED".equals(current.state())) return;
            if (cancelled && !"RESERVED".equals(current.state())) throw conflict();
            if (!id.equals(cache.activeRequest()) || input < 0 || input > current.inputBound()
                    || output < 0 || output > current.outputBound()) throw conflict();
            long charged = cost(input, output, current.inputRate(), current.outputRate());
            if (charged > current.reservedMicros() || payload != null && payload.length() > 16_384) throw conflict();
            if (mapper.settle(current.monthKey(), current.reservedMicros(), charged) != 1
                    || mapper.finish(id, cancelled ? "CANCELLED" : "SETTLED", charged, input, output,
                    cancelled ? "NOT_SENT" : payload == null ? "INVALID_OUTPUT" : "VALID_OUTPUT") != 1
                    || mapper.release(current.repositoryId(), id, payload) != 1) throw conflict();
        });
    }

    /** 每百万 token 的元价乘 token 数恰为微元，统一向上取整并防止溢出。 */
    public static long cost(int input, int output, BigDecimal inputRate, BigDecimal outputRate) {
        if (input < 0 || output < 0 || inputRate.signum() < 0 || outputRate.signum() < 0) throw conflict();
        return inputRate.multiply(BigDecimal.valueOf(input)).add(outputRate.multiply(BigDecimal.valueOf(output)))
                .setScale(0, RoundingMode.CEILING).longValueExact();
    }

    /**
     * 决策不携带密钥或正文，仅内部服务能据 GRANTED 发起新调用。
     * @param status 固定决策代号
     * @param request 仅新获准请求携带预留事实
     * @param payload 锁内看到的成功缓存
     */
    public record Decision(String status, TrendAiRequest request, String payload) { }

    private static BusinessException conflict() { return new BusinessException(ErrorCode.CONFLICT, "AI 账本状态已变化"); }
}
