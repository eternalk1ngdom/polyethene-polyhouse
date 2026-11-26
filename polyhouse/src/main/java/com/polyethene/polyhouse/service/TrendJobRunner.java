package com.polyethene.polyhouse.service;

import com.polyethene.polyhouse.common.annotation.RequireRole;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.config.*;
import com.polyethene.polyhouse.model.entity.TrendJobRecord;
import com.polyethene.polyhouse.service.trend.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** 持久队列只有一槽，本机线程也不排队；浏览器断开不撤销已经受理的更新。 */
@Component
public class TrendJobRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(TrendJobRunner.class);
    private final TrendJobStore store;
    private final GithubTrendClient source;
    private final TrendAiService ai;
    private final TrendReportService reports;
    private final TrendBriefingService briefings;
    private final boolean enabled;
    private final boolean briefingAutoEnabled;
    private final AtomicReference<Execution> active = new AtomicReference<>();
    private final AtomicBoolean wakePending = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new SynchronousQueue<>(), task -> { var thread = new Thread(task, "trend-worker"); thread.setDaemon(true); return thread; },
            new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "trend-lease"); thread.setDaemon(true); return thread;
    });

    /** 兼容既有测试构造：默认不自动串联 7 日风口简报。 */
    public TrendJobRunner(TrendJobStore store, GithubTrendClient source, TrendAiService ai, TrendReportService reports,
                          TrendProperties module, TrendJobProperties jobs) {
        this(store, source, ai, reports, null, module, jobs, new TrendBriefingProperties(false));
    }

    /** 不读取ThreadLocal或登录Token；后台显式遵守模块、任务开关、账号版本、预算和执行权。 */
    @Autowired
    public TrendJobRunner(TrendJobStore store, GithubTrendClient source, TrendAiService ai, TrendReportService reports,
                          TrendBriefingService briefings, TrendProperties module, TrendJobProperties jobs,
                          TrendBriefingProperties briefingProperties) {
        this.store = store; this.source = source; this.ai = ai; this.reports = reports; this.briefings = briefings;
        enabled = module.enabled() && jobs.enabled();
        briefingAutoEnabled = enabled && briefingProperties != null && briefingProperties.autoEnabled();
    }

    /** 仅启用任务时轮询和续租；这是执行器维护，不是每日采集定时器。 */
    @PostConstruct
    public void start() {
        if (enabled) timer.scheduleWithFixedDelay(this::pump, 0, 30, TimeUnit.SECONDS);
    }

    /** 同时最多一个唤醒事件，避免HTTP请求堆积在线程调度器内存队列。 */
    public void wake() {
        if (!enabled || closed.get() || !wakePending.compareAndSet(false, true)) return;
        try { timer.execute(() -> { wakePending.set(false); pump(); }); }
        catch (RejectedExecutionException failure) { wakePending.set(false); LOGGER.debug("Trend wake skipped during shutdown"); }
    }

    private synchronized void pump() {
        if (!enabled || closed.get()) return;
        var execution = active.get();
        if (execution != null) {
            try { store.heartbeat(execution.job); }
            catch (RuntimeException failure) { execution.cancel(); }
            return;
        }
        try {
            var job = store.claim();
            if (job == null) return;
            var next = new Execution(job);
            active.set(next);
            try { worker.execute(() -> execute(next)); }
            catch (RejectedExecutionException failure) {
                active.compareAndSet(next, null);
                store.fail(job, true);
            }
        } catch (RuntimeException failure) {
            // 防重存储异常不启动新工作，也不记录可能包含配置的底层异常。
            LOGGER.warn("Trend task pump unavailable; no external request started");
        }
    }

    /** 注解声明算力性质，真正后台授权由guard执行；内部调用不依赖MVC拦截器。 */
    @RequireRole(UserRoleEnum.INSIDER)
    private void execute(Execution execution) {
        execution.thread = Thread.currentThread();
        Runnable guard = () -> {
            if (execution.cancelled.get()) throw new TrendExecutionLost();
            store.guard(execution.job);
        };
        try (var context = MDC.putCloseable("trendJob", execution.job.id())) {
            guard.run();
            var collection = source.collect(guard, reports.missingInsightIds());
            LOGGER.info("星潮采集完成：任务={}，采集时间={}，收录数={}", execution.job.id(), collection.capturedAt(), collection.projects().size());
            if (execution.job.aiRequested()) {
                store.advance(execution.job, "ANALYZING");
                collection = ai.enrich(execution.job.id(), collection, guard);
            } else {
                collection = ai.reuseOnly(collection, guard);
            }
            store.advance(execution.job, "PUBLISHING");
            var publishedReport = store.publish(execution.job, collection);
            for (var project : collection.projects()) {
                if (project.sourceIssue() == null) continue;
                var item = project.snapshot();
                String content = item.insight() != null ? item.insightStatus()
                        : item.description() != null && !item.description().isBlank() ? "SOURCE_DESCRIPTION" : "RANKING_ONLY";
                LOGGER.info("星潮资料降级：任务={}，排名={}，内容来源={}，解读状态={}，资料原因={}",
                        execution.job.id(), item.rankNum(), content, item.insightStatus(), project.sourceIssue());
            }
            triggerScheduledBriefing(execution.job, publishedReport == null ? null : publishedReport.periodKey());
        } catch (RuntimeException failure) {
            boolean interrupted = failure instanceof TrendExecutionLost || execution.cancelled.get() || Thread.currentThread().isInterrupted();
            // 清除中断标记后完成有界数据库清理，避免把JDBC失败误作成功；付费预留不在此退款。
            Thread.interrupted();
            try { store.fail(execution.job, interrupted); }
            catch (RuntimeException cleanup) { LOGGER.warn("Trend task cleanup deferred to persistent lease: job={}", execution.job.id()); }
        } finally {
            active.compareAndSet(execution, null);
        }
    }

    /**
     * 仅在每日自动日报发布成功且独立自动简报开关启用时，以独立异常边界顺路刷新一次 7 日风口简报；
     * 任何异常或预算不足均只记脱敏日志，绝不影响已发布日报的任务成功状态。
     */
    private void triggerScheduledBriefing(TrendJobRecord job, LocalDate periodKey) {
        if (job.actorId() != null || !briefingAutoEnabled || briefings == null || periodKey == null) {
            return;
        }
        try {
            String outcome = briefings.generateForScheduled(periodKey);
            LOGGER.info("星潮自动简报串联完成：任务={}，期号={}，结果={}", job.id(), periodKey, outcome);
        } catch (RuntimeException failure) {
            LOGGER.warn("星潮自动简报串联失败，不影响已发布日报：任务={}，期号={}，异常={}",
                    job.id(), periodKey, failure.getClass().getSimpleName());
        }
    }

    /** 线程取消不等于远端请求未计费；持久账本及租约决定后续是否能继续。 */
    @PreDestroy
    public void close() {
        closed.set(true);
        var execution = active.get(); if (execution != null) execution.cancel();
        timer.shutdownNow(); worker.shutdownNow();
    }

    private static final class Execution {
        private final TrendJobRecord job;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile Thread thread;
        private Execution(TrendJobRecord job) { this.job = job; }
        private void cancel() { cancelled.set(true); var running = thread; if (running != null) running.interrupt(); }
    }
}
