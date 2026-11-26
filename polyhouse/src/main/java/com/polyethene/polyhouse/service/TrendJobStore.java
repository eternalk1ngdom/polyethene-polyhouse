package com.polyethene.polyhouse.service;

import com.polyethene.polyhouse.common.constant.*;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.config.*;
import com.polyethene.polyhouse.mapper.TrendJobMapper;
import com.polyethene.polyhouse.mapper.TrendScheduleMapper;
import com.polyethene.polyhouse.model.entity.*;
import com.polyethene.polyhouse.model.vo.*;
import com.polyethene.polyhouse.service.trend.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.*;
import java.util.List;
import java.util.Set;

/** 数据库单槽、执行代数及租约形成发布围栏；不在事务内抓取网页或请求AI。 */
@Service
public class TrendJobStore {
    private static final int RECENT_JOBS_LIMIT = 5;
    private final TrendJobMapper mapper;
    private final TrendOwnerAuthorization owners;
    private final TrendArchiveService archive;
    private final TrendProperties module;
    private final TrendJobProperties jobs;
    private final TrendAiProperties ai;
    private final TrendScheduleProperties schedule;
    private final TrendBriefingProperties briefing;
    private final TrendScheduleMapper scheduled;
    private final TransactionTemplate transaction;

    /** 所有短事务使用同一管理器；发布加入当前事务，任务结果和公开版本同步提交。 */
    public TrendJobStore(TrendJobMapper mapper, TrendOwnerAuthorization owners, TrendArchiveService archive,
                         TrendProperties module, TrendJobProperties jobs, TrendAiProperties ai, PlatformTransactionManager manager) {
        this(mapper, owners, archive, module, jobs, ai, manager, new TrendScheduleProperties(false, null, null),
                null, new TrendBriefingProperties(false));
    }

    /** 兼容既有 9 参测试构造：自动简报默认关闭。 */
    public TrendJobStore(TrendJobMapper mapper, TrendOwnerAuthorization owners, TrendArchiveService archive,
                         TrendProperties module, TrendJobProperties jobs, TrendAiProperties ai, PlatformTransactionManager manager,
                         TrendScheduleProperties schedule, TrendScheduleMapper scheduled) {
        this(mapper, owners, archive, module, jobs, ai, manager, schedule, scheduled, new TrendBriefingProperties(false));
    }

    /** 生产追加定时与自动简报配置，旧内部验收构造仍保持默认关闭，无需重放旧迁移。 */
    @Autowired
    public TrendJobStore(TrendJobMapper mapper, TrendOwnerAuthorization owners, TrendArchiveService archive,
                         TrendProperties module, TrendJobProperties jobs, TrendAiProperties ai, PlatformTransactionManager manager,
                         TrendScheduleProperties schedule, TrendScheduleMapper scheduled, TrendBriefingProperties briefing) {
        this.mapper = mapper; this.owners = owners; this.archive = archive;
        this.module = module; this.jobs = jobs; this.ai = ai;
        this.schedule = schedule; this.scheduled = scheduled;
        this.briefing = briefing == null ? new TrendBriefingProperties(false) : briefing;
        transaction = new TransactionTemplate(manager); transaction.setTimeout(5);
    }

    /** 已有请求优先返回；活动期间不排第二批，冷却和容量均由持久槽统一判断。 */
    public TrendJobSubmissionVO submit(String id, User actor) {
        requireReady();
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || actor == null || actor.getRole() != UserRoleEnum.OWNER) throw new BusinessException(ErrorCode.INVALID_PARAMETER);
        return transaction.execute(status -> {
            var slot = slot();
            var now = mapper.now();
            var existing = mapper.lockJob(id);
            var active = recover(slot, now);
            if (existing != null) {
                if (!java.util.Objects.equals(existing.actorId(), actor.getId())) throw new BusinessException(ErrorCode.FORBIDDEN);
                return new TrendJobSubmissionVO(false, true, view(mapper.lockJob(id)));
            }
            if (active != null) return new TrendJobSubmissionVO(false, false, view(active));
            if (slot.lastAcceptedAt() != null && now.isBefore(slot.lastAcceptedAt().plusSeconds(60))) {
                throw new TrendJobThrottle((Duration.between(now, slot.lastAcceptedAt().plusSeconds(60)).toMillis() + 999) / 1000);
            }
            return new TrendJobSubmissionVO(true, false, view(admit(id, actor.getId(), actor.getCredentialVersion(), slot, now)));
        });
    }

    /**
     * 数据库北京时间命中首次/补偿十分钟窗口才受理；多实例和重启共用单槽及日期唯一事实。
     * 补偿只接受首次明确FAILED/COLLECTING且无任何待核对费用，不重放已进入AI的执行。
     * @return 新受理任务，窗口外、忙、已有报告/尝试或不安全补偿时为空
     */
    public TrendJobRecord scheduleDue() {
        if (!module.enabled() || !jobs.enabled() || !schedule.ready() || scheduled == null) return null;
        return transaction.execute(status -> {
            var now = mapper.now();
            var local = now.toInstant(ZoneOffset.UTC).atZone(ZoneId.of("Asia/Shanghai"));
            int delta = local.getHour() * 60 + local.getMinute() - schedule.startMinute();
            int attempt = delta >= 0 && delta < 10 ? 0
                    : delta >= schedule.retryDelayMinutes() && delta < schedule.retryDelayMinutes() + 10 ? 1 : -1;
            if (attempt < 0) return null;
            var slot = slot();
            var checkedNow = mapper.now();
            var checkedLocal = checkedNow.toInstant(ZoneOffset.UTC).atZone(ZoneId.of("Asia/Shanghai"));
            int checkedDelta = checkedLocal.getHour() * 60 + checkedLocal.getMinute() - schedule.startMinute();
            int expectedStart = attempt == 0 ? 0 : schedule.retryDelayMinutes();
            if (!local.toLocalDate().equals(checkedLocal.toLocalDate()) || checkedDelta < expectedStart || checkedDelta >= expectedStart + 10) return null;
            now = checkedNow;
            // 单槽所有读取与后续写入使用同一事务，等待超时会安全拒绝而不排外部请求。
            var day = local.toLocalDate();
            if (recover(slot, now) != null || scheduled.lockAttempt(day, attempt) != null || scheduled.published(day)) return null;
            if (slot.lastAcceptedAt() != null && now.isBefore(slot.lastAcceptedAt().plusSeconds(60))) return null;
            if (attempt == 1) {
                var first = scheduled.lockAttempt(day, 0);
                if (first == null || mapper.hasPendingFees()) return null;
                var previous = mapper.lockJob(first.jobId());
                if (previous == null || !"FAILED".equals(previous.state()) || !"COLLECTING".equals(previous.phase())) return null;
            }
            var job = admit(java.util.UUID.randomUUID().toString(), null, null, slot, now);
            if (scheduled.insert(new TrendScheduleRecord(day, attempt, job.id(), now)) != 1) throw conflict();
            return job;
        });
    }

    private TrendJobRecord admit(String id, Long actor, Long version, TrendJobSlot slot, LocalDateTime now) {
        if (ai.enabled() && !ai.ready()) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "AI配置尚未就绪，未受理任务");
        if (!mapper.hasPendingFees()) mapper.clean(now.minusDays(90));
        if (mapper.count() >= 1000 || slot.generation() == Long.MAX_VALUE) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "任务存储已达容量，需先检查历史及待核对记录");
        long generation = slot.generation() + 1;
        var job = new TrendJobRecord(id, actor, version, ai.enabled(), generation, "QUEUED", "QUEUED", now,
                null, null, null, now.plusMinutes(30), null, null);
        if (mapper.insert(job) != 1 || mapper.occupy(id, generation, now) != 1) throw conflict();
        return job;
    }

    /** 查询也回收已过期任务，停用执行器时不会一直向屋主显示运行中。 */
    public TrendJobVO query(String id) {
        return transaction.execute(status -> {
            var slot = slot(); recover(slot, mapper.now());
            var job = mapper.lockJob(id);
            if (job == null) throw new BusinessException(ErrorCode.NOT_FOUND);
            return view(job);
        });
    }

    /**
     * 屋主只读查询每日自动采集配置、今日受理状态及最近 5 条任务记录；
     * 定时关闭时绝不访问 014 调度表，兼容仅迁移 010～013 的环境。
     */
    public TrendScheduleStatusVO scheduleStatus() {
        return transaction.execute(status -> {
            var now = mapper.now();
            var local = now.toInstant(ZoneOffset.UTC).atZone(ZoneId.of("Asia/Shanghai"));
            var today = local.toLocalDate();
            boolean scheduleEnabled = module.enabled() && jobs.enabled() && schedule.ready() && scheduled != null;
            boolean todayPublished = scheduled != null && scheduled.published(today);
            var recentRecords = mapper.recent(RECENT_JOBS_LIMIT);
            // 最近 5 条任务列表只展示触发来源、状态与时间，避免无谓读取并反序列化 5 份完整日报 JSON。
            List<TrendJobVO> recentJobs = recentRecords == null ? List.of()
                    : recentRecords.stream().map(record -> view(record, false)).toList();

            String todayStatus = resolveTodayStatus(scheduleEnabled, todayPublished, today, local, recentRecords);
            return new TrendScheduleStatusVO(
                    module.enabled(),
                    jobs.enabled(),
                    scheduleEnabled,
                    scheduleEnabled && briefing.autoEnabled(),
                    ai.ready(),
                    schedule.dailyTime(),
                    schedule.retryDelayMinutes(),
                    today,
                    todayStatus,
                    todayPublished,
                    recentJobs);
        });
    }

    private String resolveTodayStatus(boolean scheduleEnabled, boolean todayPublished, LocalDate today,
                                      ZonedDateTime local, List<TrendJobRecord> recentRecords) {
        if (!scheduleEnabled) return "DISABLED";
        var attempts = scheduled.byDay(today);
        List<TrendJobRecord> todayScheduledJobs = new java.util.ArrayList<>();
        if (attempts != null) {
            for (var attempt : attempts) {
                var matched = findJob(recentRecords, attempt.jobId());
                if (matched == null) matched = mapper.lockJob(attempt.jobId());
                if (matched != null) todayScheduledJobs.add(matched);
            }
        }
        if (todayScheduledJobs.stream().anyMatch(j -> "QUEUED".equals(j.state()) || "RUNNING".equals(j.state()))) {
            return "RUNNING";
        }
        if (todayPublished) {
            boolean publishedByAuto = todayScheduledJobs.stream().anyMatch(j -> j.reportId() != null);
            return publishedByAuto ? "PUBLISHED_AUTO" : "PUBLISHED_MANUAL";
        }
        int nowMinute = local.getHour() * 60 + local.getMinute();
        int startMinute = schedule.startMinute();
        int retryEndMinute = startMinute + schedule.retryDelayMinutes() + 10;
        if (attempts != null && attempts.size() == 1 && attempts.getFirst().attempt() == 0 && !todayScheduledJobs.isEmpty()) {
            var firstJob = todayScheduledJobs.getFirst();
            if ("FAILED".equals(firstJob.state()) && "COLLECTING".equals(firstJob.phase()) && nowMinute < retryEndMinute) {
                return "RETRY_PENDING";
            }
        }
        if (!todayScheduledJobs.isEmpty()) {
            return "FAILED";
        }
        return nowMinute < startMinute + 10 ? "WAITING_WINDOW" : "MISSED_WINDOW";
    }

    private static TrendJobRecord findJob(List<TrendJobRecord> recent, String jobId) {
        if (recent == null || jobId == null) return null;
        for (var item : recent) {
            if (jobId.equals(item.id())) return item;
        }
        return null;
    }

    /**
     * 人工费用处理必须与新任务受理/运行互斥；调用方先锁屋主，持有本槽直到审计和账本一起提交。
     * 不单独开启后立即提交事务，避免释放槽后才修改计费状态；过期任务只标中断不重放。
     * @return 同一数据库的UTC时间，供人工处理的五分钟窗口判断
     */
    public LocalDateTime lockIdleForFeeResolution() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Fee resolution requires an outer transaction");
        var slot = slot(); var now = mapper.now();
        if (recover(slot, now) != null) throw new BusinessException(ErrorCode.CONFLICT, "请等待当前采集任务结束后再处理费用");
        return now;
    }

    /** 单工作线程领取持久QUEUED；过期RUNNING只标中断，绝不重放可能已经计费的执行。 */
    public TrendJobRecord claim() {
        requireReady();
        return transaction.execute(status -> {
            var slot = slot(); var now = mapper.now();
            var job = recover(slot, now);
            if (job == null || !"QUEUED".equals(job.state())) return null;
            // 多实例可能只在部分节点启用定时；无资格的节点不能先抢走系统任务再将其误标中断。
            if (job.actorId() == null && (!schedule.ready() || scheduled == null)) return null;
            if (mapper.start(job.id(), now, earlier(now.plusMinutes(2), job.deadline())) != 1) throw conflict();
            return mapper.lockJob(job.id());
        });
    }

    /** 每个外部请求前在短事务中核对最新角色及执行权；不依赖只在MVC生效的注解。 */
    public void guard(TrendJobRecord job) { checked(job, null, false); }

    /** 心跳只能延续尚未过期的同代执行，不能复活已被新实例回收的任务。 */
    public void heartbeat(TrendJobRecord job) { checked(job, null, true); }

    /** 可观察阶段在启动对应工作前写入；阶段不能由用户任意指定。 */
    public void advance(TrendJobRecord job, String phase) {
        if (!Set.of("ANALYZING", "PUBLISHING").contains(phase)) throw new IllegalArgumentException("Unsupported job phase");
        checked(job, phase, false);
    }

    /** 单槽检查、不可变版本、公开指针、终结和释放同事务，旧任务无法抢在新版本之后发布。 */
    public TrendReportVO publish(TrendJobRecord job, TrendCollection collection) {
        requireReady();
        return transaction.execute(status -> {
            authorize(job);
            var slot = slot(); var now = mapper.now();
            current(job, slot, now);
            var report = archive.publish(job.id(), collection);
            finish(job, report.insightComplete() ? "SUCCEEDED" : "PARTIAL", report.reportId(), null, now);
            return report;
        });
    }

    /** 失败清理只匹配槽和代数；不因旧线程晚退出而伤及新任务，原因只允许固定白名单。 */
    public void fail(TrendJobRecord job, boolean interrupted) {
        transaction.executeWithoutResult(status -> {
            var slot = slot();
            if (!job.id().equals(slot.activeId()) || job.generation() != slot.generation()) return;
            finish(job, interrupted ? "INTERRUPTED" : "FAILED", null,
                    interrupted ? "EXECUTION_REVOKED" : "EXECUTION_FAILED", mapper.now());
        });
    }

    private void checked(TrendJobRecord job, String phase, boolean heartbeat) {
        if (Thread.currentThread().isInterrupted()) throw new TrendExecutionLost();
        try {
            requireReady();
            transaction.executeWithoutResult(status -> {
                authorize(job);
                var slot = slot(); var now = mapper.now();
                var current = current(job, slot, now);
                if (phase != null && mapper.phase(job.id(), job.generation(), phase) != 1) throw conflict();
                if (heartbeat && mapper.heartbeat(job.id(), job.generation(), earlier(now.plusMinutes(2), current.deadline())) != 1) throw conflict();
            });
        } catch (RuntimeException failure) { throw new TrendExecutionLost(); }
    }

    private TrendJobRecord current(TrendJobRecord expected, TrendJobSlot slot, LocalDateTime now) {
        if (!expected.id().equals(slot.activeId()) || expected.generation() != slot.generation()) throw new TrendExecutionLost();
        var current = mapper.lockJob(expected.id());
        if (current == null || current.generation() != expected.generation() || !"RUNNING".equals(current.state())
                || current.leaseUntil() == null || !now.isBefore(current.leaseUntil()) || !now.isBefore(current.deadline())) throw new TrendExecutionLost();
        return current;
    }

    private void authorize(TrendJobRecord job) {
        if (job.actorId() != null && job.credentialVersion() != null) {
            owners.lockActor(job.actorId(), job.credentialVersion());
            return;
        }
        if (job.actorId() != null || job.credentialVersion() != null || !schedule.ready() || scheduled == null) throw new TrendExecutionLost();
        var intent = scheduled.byJob(job.id());
        if (intent == null || !intent.createdAt().equals(job.createdAt()) || !intent.periodDate().equals(
                job.createdAt().toInstant(ZoneOffset.UTC).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate())) throw new TrendExecutionLost();
    }

    private TrendJobRecord recover(TrendJobSlot slot, LocalDateTime now) {
        if (slot.activeId() == null) return null;
        var job = mapper.lockJob(slot.activeId());
        if (job == null || job.generation() != slot.generation()) throw conflict();
        if (!now.isBefore(job.deadline()) || "RUNNING".equals(job.state())
                && (job.leaseUntil() == null || !now.isBefore(job.leaseUntil()))) {
            finish(job, "INTERRUPTED", null, "LEASE_EXPIRED", now);
            return null;
        }
        if (!Set.of("QUEUED", "RUNNING").contains(job.state())) throw conflict();
        return job;
    }
    private TrendJobSlot slot() {
        var slot = mapper.lockSlot(); if (slot == null) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE); return slot;
    }
    private void finish(TrendJobRecord job, String state, String report, String reason, LocalDateTime now) {
        if (mapper.finish(job.id(), job.generation(), state, report, reason, now) != 1
                || mapper.release(job.id(), job.generation()) != 1) throw conflict();
    }
    private void requireReady() {
        if (!module.enabled() || !jobs.enabled()) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "技术雷达任务尚未启用");
    }
    private TrendJobVO view(TrendJobRecord job) {
        return view(job, true);
    }
    private TrendJobVO view(TrendJobRecord job, boolean includeSummary) {
        return new TrendJobVO(job.id(), job.state(), job.phase(), job.aiRequested(), instant(job.createdAt()), instant(job.startedAt()),
                instant(job.completedAt()), instant(job.deadline()), job.reportId(), job.reason(), job.actorId() == null ? "SCHEDULED" : "MANUAL",
                includeSummary && job.reportId() != null ? archive.summary(job.reportId()) : null);
    }
    private static Instant instant(LocalDateTime value) { return value == null ? null : value.toInstant(ZoneOffset.UTC); }
    private static LocalDateTime earlier(LocalDateTime a, LocalDateTime b) { return a.isBefore(b) ? a : b; }
    private static BusinessException conflict() { return new BusinessException(ErrorCode.CONFLICT, "任务执行状态已变化"); }
}
