package com.polyethene.polyhouse.service;

import com.polyethene.polyhouse.common.annotation.RequireRole;
import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.config.TrendAiProperties;
import com.polyethene.polyhouse.config.TrendProperties;
import com.polyethene.polyhouse.model.dto.TrendBriefingGenerateDTO;
import com.polyethene.polyhouse.model.dto.TrendBriefingQueryDTO;
import com.polyethene.polyhouse.model.entity.TrendBriefingRecord;
import com.polyethene.polyhouse.model.vo.TrendBriefingContentVO;
import com.polyethene.polyhouse.model.vo.TrendBriefingGenerateVO;
import com.polyethene.polyhouse.model.vo.TrendBriefingVO;
import com.polyethene.polyhouse.service.trend.DeepSeekGateway;
import com.polyethene.polyhouse.service.trend.TrendBriefingCodec;
import com.polyethene.polyhouse.service.trend.TrendBriefingInputBuilder;
import com.polyethene.polyhouse.service.trend.TrendJobThrottle;
import com.polyethene.polyhouse.service.trend.TrendOwnerAuthorization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/**
 * 7 日风口简报服务：匿名只读与屋主同步有界生成分离；
 * 同指纹零费用复用、网络调用严格置于数据库事务之外，超时与拒收均由独立账本兜底。
 */
@Service
public class TrendBriefingService {
    private static final Logger LOGGER = LoggerFactory.getLogger(TrendBriefingService.class);
    /** 连续付费尝试的最小冷却秒数，防止屋主误双击或高频触发。 */
    static final long COOLDOWN_SECONDS = 15;

    private final TrendReportService reports;
    private final TrendBriefingLedger ledger;
    private final TrendBriefingCodec codec;
    private final DeepSeekGateway gateway;
    private final TrendOwnerAuthorization owners;
    private final TrendProperties module;
    private final TrendAiProperties ai;
    private final Clock clock;
    private final TransactionTemplate authTransaction;
    private final Semaphore active = new Semaphore(1);
    private final Object cooldownLock = new Object();
    private Instant lastPaidAttemptAt;

    /** 复用既有日报快照服务、官方 DeepSeek 网关与屋主会话核验，不另建独立线程队列。 */
    public TrendBriefingService(TrendReportService reports, TrendBriefingLedger ledger, TrendBriefingCodec codec,
                                DeepSeekGateway gateway, TrendOwnerAuthorization owners, TrendProperties module,
                                TrendAiProperties ai, Clock clock, PlatformTransactionManager manager) {
        this.reports = reports;
        this.ledger = ledger;
        this.codec = codec;
        this.gateway = gateway;
        this.owners = owners;
        this.module = module;
        this.ai = ai;
        this.clock = clock;
        this.authTransaction = new TransactionTemplate(manager);
        this.authTransaction.setTimeout(5);
    }

    /**
     * 匿名只读查询指定锚点日（默认最新已发布日）的 7 日风口简报；
     * 锚点日无已发布日报返回 404，有日报但无有效简报返回 HTTP 200 且 status=ABSENT，绝不触发生成。
     */
    public TrendBriefingVO query(TrendBriefingQueryDTO request) {
        String periodKey = request == null ? null : request.periodKey();
        var snapshot = reports.windowSnapshot(periodKey);
        var input = TrendBriefingInputBuilder.build(snapshot.anchorDate(), snapshot.reports());
        TrendBriefingRecord saved = readBriefing(snapshot.anchorDate());
        return toView(input, saved);
    }

    /**
     * 屋主按需同步触发 7 日风口简报生成；需同时满足 OWNER 身份核验与 INSIDER 算力门槛。
     * 同锚点日、同指纹、同模型规则已有有效稿时零费用返回 REUSED；外部网络调用不持有数据库事务。
     */
    @RequireRole(UserRoleEnum.INSIDER)
    public TrendBriefingGenerateVO generate(TrendBriefingGenerateDTO request, String token) {
        verifyOwnerSession(token);
        if (!module.enabled()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        String periodKey = request == null ? null : request.periodKey();
        var snapshot = reports.windowSnapshot(periodKey);
        var input = TrendBriefingInputBuilder.build(snapshot.anchorDate(), snapshot.reports());
        input.requireEligible();
        return executeGenerate(input, true);
    }

    /**
     * 每日自动日报发布成功后的系统顺路简报生成入口：复用同指纹零费用防重与 10 元共享月预算红线；
     * 样本不足或配置未就绪时安全跳过，绝不抛出未受控异常影响已发布日报。
     * @param anchorDate 刚发布日报的北京期号
     * @return 结果状态码（GENERATED / REUSED / BUDGET_LIMIT / PENDING / INVALID_OUTPUT / UNAVAILABLE / SKIPPED_*）
     */
    @RequireRole(UserRoleEnum.INSIDER)
    public String generateForScheduled(LocalDate anchorDate) {
        if (!module.enabled() || !ai.ready()) {
            return "SKIPPED_DISABLED";
        }
        var snapshot = reports.windowSnapshot(anchorDate == null ? null : anchorDate.toString());
        var input = TrendBriefingInputBuilder.build(snapshot.anchorDate(), snapshot.reports());
        if (!input.eligible()) {
            return "SKIPPED_INELIGIBLE";
        }
        return executeGenerate(input, false).outcome();
    }

    private TrendBriefingGenerateVO executeGenerate(TrendBriefingInputBuilder.BriefingInput input, boolean checkCooldown) {
        LocalDate anchorDate = input.stats().anchorDate();
        TrendBriefingRecord existing = readBriefing(anchorDate);
        if (isFreshReusable(existing, input)) {
            var content = safeCached(existing.payload());
            if (content != null) {
                return new TrendBriefingGenerateVO("REUSED", false, readyView(input, existing, content, false));
            }
        }
        if (!ai.ready()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "星潮 AI 解读未启用或配置未就绪");
        }
        if (!active.tryAcquire()) {
            return new TrendBriefingGenerateVO("PENDING", false, toView(input, readBriefing(anchorDate)));
        }
        try {
            if (checkCooldown) {
                enforceCooldown();
            }
            var prepared = codec.prepare(input, ai.model(), ai.thinkingActive(), ai.thinkingEffort());
            String requestId = UUID.randomUUID().toString();
            String expectedFingerprint = existing == null ? null : existing.sourceFingerprint();
            TrendBriefingLedger.Decision decision;
            try {
                decision = ledger.reserve(requestId, anchorDate, input.sourceFingerprint(),
                        prepared.inputBound(), prepared.outputBound(), expectedFingerprint);
            } catch (DataAccessException | TransactionException failure) {
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
            }

            if ("REUSED".equals(decision.status())) {
                return new TrendBriefingGenerateVO("REUSED", false, toView(input, decision.briefing()));
            }
            if ("PENDING".equals(decision.status()) || "BUDGET_LIMIT".equals(decision.status())) {
                return new TrendBriefingGenerateVO(decision.status(), false, toView(input, decision.briefing()));
            }
            if (!"GRANTED".equals(decision.status())) {
                throw new BusinessException(ErrorCode.CONFLICT, "风口简报状态已更新，请稍后重试");
            }

            final DeepSeekGateway.Reply reply;
            try {
                reply = gateway.complete(prepared.body());
            } catch (DeepSeekGateway.NotSent failure) {
                cancelBeforeSend(requestId);
                return new TrendBriefingGenerateVO("UNAVAILABLE", false, toView(input, readBriefing(anchorDate)));
            } catch (RuntimeException failure) {
                markUnknown(requestId, false);
                return new TrendBriefingGenerateVO("PENDING", true, toView(input, readBriefing(anchorDate)));
            }

            if (reply.status() != 200) {
                LOGGER.warn("风口简报上游响应非 200，已转入待核对：请求编号={}，状态码={}", requestId, reply.status());
                markUnknown(requestId, false);
                return new TrendBriefingGenerateVO("PENDING", true, toView(input, readBriefing(anchorDate)));
            }

            final TrendBriefingCodec.Envelope envelope;
            try {
                envelope = codec.envelope(reply.body());
            } catch (BusinessException failure) {
                markUnknown(requestId, false);
                return new TrendBriefingGenerateVO("PENDING", true, toView(input, readBriefing(anchorDate)));
            }

            if (envelope.input() > prepared.inputBound() || envelope.output() > prepared.outputBound()) {
                markUnknown(requestId, true);
                return new TrendBriefingGenerateVO("PENDING", true, toView(input, readBriefing(anchorDate)));
            }

            final TrendBriefingContentVO content;
            try {
                content = codec.parse(envelope.content(), input.allowedReposByLowerName());
            } catch (TrendBriefingCodec.OutputRejected failure) {
                settleSafely(requestId, envelope.input(), envelope.output(), null);
                LOGGER.warn("7 日风口简报未通过校验：请求编号={}，原因={}", requestId, failure.reason());
                return new TrendBriefingGenerateVO("INVALID_OUTPUT", true, toView(input, readBriefing(anchorDate)));
            }

            Instant generatedAt = clock.instant();
            var stats = input.stats();
            var payload = new TrendBriefingLedger.SettlementPayload(stats.windowStart(), stats.windowEnd(),
                    stats.coveredDays(), stats.sampleStatus(), codec.encode(content), generatedAt);
            settleSafely(requestId, envelope.input(), envelope.output(), payload);
            TrendBriefingRecord latest = readBriefing(anchorDate);
            return new TrendBriefingGenerateVO("GENERATED", true,
                    latest != null && latest.payload() != null ? toView(input, latest)
                            : readyView(input, new TrendBriefingRecord(anchorDate, stats.windowStart(), stats.windowEnd(),
                                    stats.coveredDays(), stats.sampleStatus(), input.sourceFingerprint(), ai.model(),
                                    TrendBriefingCodec.RULE, null, payload.payloadJson(),
                                    java.time.LocalDateTime.ofInstant(generatedAt, ZoneOffset.UTC)), content, false));
        } finally {
            active.release();
        }
    }

    private void verifyOwnerSession(String token) {
        try {
            authTransaction.executeWithoutResult(status -> owners.lockSession(token));
        } catch (DataAccessException | TransactionException failure) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private void enforceCooldown() {
        Instant now = clock.instant();
        synchronized (cooldownLock) {
            if (lastPaidAttemptAt != null) {
                long elapsed = Duration.between(lastPaidAttemptAt, now).getSeconds();
                if (elapsed >= 0 && elapsed < COOLDOWN_SECONDS) {
                    throw new TrendJobThrottle(COOLDOWN_SECONDS - elapsed);
                }
            }
            lastPaidAttemptAt = now;
        }
    }

    private TrendBriefingRecord readBriefing(LocalDate anchorDate) {
        try {
            return ledger.briefing(anchorDate);
        } catch (DataAccessException failure) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private boolean isFreshReusable(TrendBriefingRecord saved, TrendBriefingInputBuilder.BriefingInput input) {
        return saved != null && saved.payload() != null
                && input.sourceFingerprint().equals(saved.sourceFingerprint())
                && ai.model().equals(saved.model())
                && TrendBriefingCodec.RULE.equals(saved.ruleVersion());
    }

    private TrendBriefingVO toView(TrendBriefingInputBuilder.BriefingInput input, TrendBriefingRecord saved) {
        if (saved == null || saved.payload() == null) {
            return absentView(input);
        }
        TrendBriefingContentVO content = safeCached(saved.payload());
        if (content == null) {
            return absentView(input);
        }
        boolean stale = !input.sourceFingerprint().equals(saved.sourceFingerprint())
                || !ai.model().equals(saved.model())
                || !TrendBriefingCodec.RULE.equals(saved.ruleVersion());
        return readyView(input, saved, content, stale);
    }

    private TrendBriefingVO absentView(TrendBriefingInputBuilder.BriefingInput input) {
        var stats = input.stats();
        return new TrendBriefingVO("ABSENT", stats.anchorDate(), stats.windowStart(), stats.windowEnd(),
                stats.coveredDays(), stats.sampleStatus(), stats.coveredDays(),
                stats.windowSummary().radar3ReadyCount(), input.eligible(), input.ineligibleReason(),
                false, null, null, null, null, null, List.of(), List.of());
    }

    private TrendBriefingVO readyView(TrendBriefingInputBuilder.BriefingInput input, TrendBriefingRecord saved,
                                      TrendBriefingContentVO content, boolean stale) {
        var stats = input.stats();
        var candidatesByLower = input.candidatesByLowerName();
        List<TrendBriefingVO.ThemeView> themes = content.themes().stream().map(theme -> {
            List<TrendBriefingVO.EvidenceRepo> repos = theme.evidenceRepos().stream().map(fullName -> {
                var matched = candidatesByLower.get(fullName.toLowerCase(Locale.ROOT));
                if (matched != null) {
                    return new TrendBriefingVO.EvidenceRepo(matched.fullName(), matched.repositoryId(),
                            matched.repoUrl(), matched.inAnchorDay());
                }
                return new TrendBriefingVO.EvidenceRepo(fullName, null, "https://github.com/" + fullName, false);
            }).toList();
            return new TrendBriefingVO.ThemeView(theme.title(), theme.signalType(), theme.summary(), repos);
        }).toList();

        Instant generatedAt = saved.generatedAt() == null ? null : saved.generatedAt().toInstant(ZoneOffset.UTC);
        int coveredDays = saved.coveredDays() == null ? stats.coveredDays() : saved.coveredDays();
        String sampleStatus = saved.sampleStatus() == null ? stats.sampleStatus() : saved.sampleStatus();
        LocalDate windowStart = saved.windowStart() == null ? stats.windowStart() : saved.windowStart();
        LocalDate windowEnd = saved.windowEnd() == null ? stats.windowEnd() : saved.windowEnd();

        return new TrendBriefingVO("READY", stats.anchorDate(), windowStart, windowEnd,
                coveredDays, sampleStatus, stats.coveredDays(), stats.windowSummary().radar3ReadyCount(),
                input.eligible(), input.ineligibleReason(), stale, generatedAt, saved.model(), saved.ruleVersion(),
                content.headline(), content.overview(), themes, content.watchouts());
    }

    private TrendBriefingContentVO safeCached(String payload) {
        try {
            return codec.cached(payload);
        } catch (BusinessException failure) {
            LOGGER.warn("风口简报持久化内容未通过复验，已回退为 ABSENT，不输出原文");
            return null;
        }
    }

    private void cancelBeforeSend(String requestId) {
        try {
            ledger.cancelBeforeSend(requestId);
        } catch (DataAccessException | TransactionException | BusinessException failure) {
            LOGGER.warn("未发送风口简报预留退回失败，需待核对：请求编号={}", requestId);
        }
    }

    private void markUnknown(String requestId, boolean usageExceeded) {
        try {
            ledger.unknown(requestId, usageExceeded);
        } catch (DataAccessException | TransactionException | BusinessException failure) {
            LOGGER.warn("风口简报未知态更新失败，原预留与执行槽仍保留：请求编号={}", requestId);
        }
    }

    private void settleSafely(String requestId, int input, int output, TrendBriefingLedger.SettlementPayload payload) {
        try {
            ledger.settle(requestId, input, output, payload);
        } catch (DataAccessException | TransactionException failure) {
            LOGGER.warn("风口简报结算入库失败，原预留需待核对：请求编号={}", requestId);
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }
}
