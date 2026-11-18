package com.polyethene.polyhouse.service;

import com.polyethene.polyhouse.common.annotation.RequireRole;
import com.polyethene.polyhouse.common.constant.*;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.config.*;
import com.polyethene.polyhouse.model.entity.TrendCollection;
import com.polyethene.polyhouse.model.vo.TrendInsightVO;
import com.polyethene.polyhouse.service.trend.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** 将复用、付费授权和失败降级串联，不从当前线程伪造后台任务的用户身份。 */
@Service
public class TrendAiService {
    private static final Logger LOGGER = LoggerFactory.getLogger(TrendAiService.class);
    private final TrendAiLedger ledger;
    private final DeepSeekGateway gateway;
    private final TrendInsightCodec codec;
    private final TrendAiProperties settings;
    private final TrendProperties module;
    private final Clock clock;
    private final Semaphore active = new Semaphore(1);

    /** 依赖均属模块边界，网络在账本事务之外执行，默认配置不触发付费。 */
    public TrendAiService(TrendAiLedger ledger, DeepSeekGateway gateway, TrendInsightCodec codec,
                           TrendAiProperties settings, TrendProperties module, Clock clock) {
        this.ledger = ledger;
        this.gateway = gateway;
        this.codec = codec;
        this.settings = settings;
        this.module = module;
        this.clock = clock;
    }

    /** 同一执行UUID与仓库决定付费意图；兼容内部验收，生产任务使用带执行权检查的重载。 */
    @RequireRole(UserRoleEnum.INSIDER)
    public TrendCollection enrich(String executionId, TrendCollection collection) {
        return enrich(executionId, collection, () -> { });
    }

    /** 在每个项目及发送前复核执行权；取消尚未发送的预留，未知远端结果仍保留账本占用。 */
    @RequireRole(UserRoleEnum.INSIDER)
    public TrendCollection enrich(String executionId, TrendCollection collection, Runnable guard) {
        Objects.requireNonNull(guard);
        if (executionId == null || !executionId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || collection == null || collection.projects().size() > 25) throw new BusinessException(ErrorCode.INVALID_PARAMETER);
        if (!module.enabled() || !settings.enabled()) return collection;
        if (!active.tryAcquire()) return new TrendCollection(collection.capturedAt(), collection.projects().stream()
                .map(project -> result(project, null, "BUSY")).toList());
        try {
            List<TrendCollection.Project> projects = new ArrayList<>();
            var paused = new AtomicBoolean(); // 仅本次执行的上游熔断，已缓存内容仍可复用。
            for (var project : collection.projects()) {
                guard.run();
                try { projects.add(analyze(executionId, project, paused, guard)); }
                catch (BusinessException | DataAccessException failure) {
                    projects.add(result(project, null, "UNAVAILABLE"));
                }
            }
            return new TrendCollection(collection.capturedAt(), projects);
        } finally { active.release(); }
    }

    private TrendCollection.Project analyze(String executionId, TrendCollection.Project project, AtomicBoolean paused, Runnable guard) {
        var item = project.snapshot();
        if ("DOC_LIST".equals(item.repoType())) return result(project, null, "NOT_REQUIRED");
        if (item.repositoryId() == null || !item.repositoryId().matches("[1-9][0-9]{0,18}")) return result(project, null, "UNAVAILABLE");
        final long repo;
        try { repo = Long.parseLong(item.repositoryId()); }
        catch (NumberFormatException failure) { return result(project, null, "UNAVAILABLE"); }
        var repository = ledger.repository(repo);
        String previousPayload = repository == null ? null : repository.payload();
        TrendInsightVO previous = cached(previousPayload);
        if (fresh(previous, project)) return result(project, previous, "REUSED");
        if (paused.get() || !settings.ready() || project.readmeVersion() == null
                || !project.readmeVersion().matches("(?:[a-f0-9]{40}|[a-f0-9]{64})")
                || project.readmeText() == null || project.readmeText().isBlank()) return fallback(project, previous, "UNAVAILABLE");
        try {
            var prepared = codec.prepare(project, settings.model());
            String id = UUID.nameUUIDFromBytes((executionId + ":" + repo).getBytes(StandardCharsets.UTF_8)).toString();
            guard.run();
            var decision = ledger.reserve(id, repo, project.readmeVersion(), prepared.inputBound(), prepared.outputBound(), previousPayload);
            if (!"GRANTED".equals(decision.status())) {
                var latest = cached(decision.payload());
                return fresh(latest, project) ? result(project, latest, "REUSED") : fallback(project, latest, decision.status());
            }
            try { guard.run(); }
            catch (TrendExecutionLost revoked) {
                try { ledger.cancelBeforeSend(id); }
                catch (RuntimeException cleanup) { LOGGER.warn("Unsent AI reservation requires reconciliation: request={}", id); }
                throw revoked;
            }
            final DeepSeekGateway.Reply reply;
            try { reply = gateway.complete(prepared.body()); }
            catch (DeepSeekGateway.NotSent failure) {
                paused.set(true);
                ledger.cancelBeforeSend(id);
                return fallback(project, previous, "UNAVAILABLE");
            } catch (RuntimeException failure) {
                paused.set(true);
                uncertain(id, false);
                return fallback(project, previous, "PENDING");
            }
            if (reply.status() != 200) {
                paused.set(true);
                LOGGER.warn("AI request requires reconciliation: request={}, status={}", id, reply.status());
                uncertain(id, false);
                return fallback(project, previous, "PENDING");
            }
            final TrendInsightCodec.Envelope envelope;
            try { envelope = codec.envelope(reply.body()); }
            catch (BusinessException failure) {
                paused.set(true);
                uncertain(id, false);
                return fallback(project, previous, "PENDING");
            }
            if (envelope.input() > prepared.inputBound() || envelope.output() > prepared.outputBound()) {
                paused.set(true);
                uncertain(id, true);
                return fallback(project, previous, "PENDING");
            }
            final TrendInsightVO insight;
            try { insight = codec.insight(envelope.content(), project.readmeVersion(), settings.model(), clock.instant()); }
            catch (TrendInsightCodec.OutputRejected failure) {
                ledger.settle(id, envelope.input(), envelope.output(), null);
                LOGGER.warn("AI 解读未通过校验：请求编号={}，原因={}", id, failure.reason());
                return fallback(project, previous, "INVALID_OUTPUT");
            }
            ledger.settle(id, envelope.input(), envelope.output(), codec.encode(insight));
            return result(project, insight, "ANALYZED");
        } catch (BusinessException | DataAccessException failure) {
            return fallback(project, previous, "UNAVAILABLE");
        }
    }

    private TrendInsightVO cached(String payload) {
        try { return codec.cached(payload); }
        catch (BusinessException failure) {
            LOGGER.warn("AI cached insight rejected; no raw content logged");
            return null;
        }
    }
    private boolean fresh(TrendInsightVO insight, TrendCollection.Project project) {
        return insight != null && Objects.equals(project.readmeVersion(), insight.readmeVersion())
                && settings.model().equals(insight.model()) && TrendInsightCodec.RULE.equals(insight.ruleVersion())
                && !clock.instant().isBefore(insight.analyzedAt())
                && Duration.between(insight.analyzedAt(), clock.instant()).compareTo(Duration.ofDays(15)) < 0;
    }
    private void uncertain(String id, boolean exceeded) {
        try { ledger.unknown(id, exceeded); }
        catch (DataAccessException | BusinessException failure) {
            // 即使记 UNKNOWN 失败，原持久 RESERVED 和仓库槽仍保留，不能退额或重发。
            LOGGER.warn("AI ledger reconciliation pending: request={}", id);
        }
    }
    private static TrendCollection.Project fallback(TrendCollection.Project project, TrendInsightVO old, String reason) {
        return result(project, old, old == null ? reason : "STALE");
    }
    private static TrendCollection.Project result(TrendCollection.Project project, TrendInsightVO insight, String status) {
        return new TrendCollection.Project(project.snapshot().withInsight(status, insight), project.readmeVersion(), project.readmeText());
    }
}
