package com.polyethene.polyhouse.service;

import com.polyethene.polyhouse.common.constant.*;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.config.*;
import com.polyethene.polyhouse.mapper.TrendJobMapper;
import com.polyethene.polyhouse.model.entity.*;
import com.polyethene.polyhouse.model.vo.*;
import com.polyethene.polyhouse.service.trend.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.*;
import java.util.Set;

/** 数据库单槽、执行代数及租约形成发布围栏；不在事务内抓取网页或请求AI。 */
@Service
public class TrendJobStore {
    private final TrendJobMapper mapper;
    private final TrendOwnerAuthorization owners;
    private final TrendArchiveService archive;
    private final TrendProperties module;
    private final TrendJobProperties jobs;
    private final TrendAiProperties ai;
    private final TransactionTemplate transaction;

    /** 所有短事务使用同一管理器；发布加入当前事务，任务结果和公开版本同步提交。 */
    public TrendJobStore(TrendJobMapper mapper, TrendOwnerAuthorization owners, TrendArchiveService archive,
                         TrendProperties module, TrendJobProperties jobs, TrendAiProperties ai, PlatformTransactionManager manager) {
        this.mapper = mapper; this.owners = owners; this.archive = archive;
        this.module = module; this.jobs = jobs; this.ai = ai;
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
                if (existing.actorId() != actor.getId()) throw new BusinessException(ErrorCode.FORBIDDEN);
                return new TrendJobSubmissionVO(false, true, view(mapper.lockJob(id)));
            }
            if (active != null) return new TrendJobSubmissionVO(false, false, view(active));
            if (slot.lastAcceptedAt() != null && now.isBefore(slot.lastAcceptedAt().plusSeconds(60))) {
                throw new TrendJobThrottle((Duration.between(now, slot.lastAcceptedAt().plusSeconds(60)).toMillis() + 999) / 1000);
            }
            if (ai.enabled() && !ai.ready()) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "AI配置尚未就绪，未受理任务");
            if (!mapper.hasPendingFees()) mapper.clean(now.minusDays(90));
            if (mapper.count() >= 1000 || slot.generation() == Long.MAX_VALUE) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "任务存储已达容量，需先检查历史及待核对记录");
            long generation = slot.generation() + 1;
            var job = new TrendJobRecord(id, actor.getId(), actor.getCredentialVersion(), ai.enabled(), generation,
                    "QUEUED", "QUEUED", now, null, null, null, now.plusMinutes(30), null, null);
            if (mapper.insert(job) != 1 || mapper.occupy(id, generation, now) != 1) throw conflict();
            return new TrendJobSubmissionVO(true, false, view(job));
        });
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
            owners.lockActor(job.actorId(), job.credentialVersion());
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
                owners.lockActor(job.actorId(), job.credentialVersion());
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
    private static TrendJobVO view(TrendJobRecord job) {
        return new TrendJobVO(job.id(), job.state(), job.phase(), job.aiRequested(), instant(job.createdAt()), instant(job.startedAt()),
                instant(job.completedAt()), instant(job.deadline()), job.reportId(), job.reason());
    }
    private static Instant instant(LocalDateTime value) { return value == null ? null : value.toInstant(ZoneOffset.UTC); }
    private static LocalDateTime earlier(LocalDateTime a, LocalDateTime b) { return a.isBefore(b) ? a : b; }
    private static BusinessException conflict() { return new BusinessException(ErrorCode.CONFLICT, "任务执行状态已变化"); }
}
