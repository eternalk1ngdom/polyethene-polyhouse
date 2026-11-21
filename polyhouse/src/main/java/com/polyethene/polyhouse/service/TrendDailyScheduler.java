package com.polyethene.polyhouse.service;

import com.polyethene.polyhouse.config.*;
import com.polyethene.polyhouse.common.annotation.RequireRole;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import jakarta.annotation.*;
import org.slf4j.*;
import org.springframework.stereotype.Component;
import java.util.concurrent.*;

/** 在运行中的应用内检测受理窗口，进程停止时无法唤醒自己，不提供公网机器触发接口。 */
@Component
public class TrendDailyScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(TrendDailyScheduler.class);
    private final TrendJobStore store;
    private final TrendJobRunner runner;
    private final boolean enabled;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "trend-daily"); thread.setDaemon(true); return thread;
    });

    /** 开关全部显式启用才创建定时线程；付费仍经持久任务、账本和固定外部客户端。 */
    public TrendDailyScheduler(TrendJobStore store, TrendJobRunner runner, TrendProperties module,
                               TrendJobProperties jobs, TrendScheduleProperties schedule) {
        this.store = store; this.runner = runner; enabled = module.enabled() && jobs.enabled() && schedule.ready();
    }

    /** 三十秒仅检查数据库时钟/窗口；日期尝试事实和任务槽保证重启、多实例防重。 */
    @PostConstruct
    public void start() { if (enabled) timer.scheduleWithFixedDelay(this::tick, 0, 30, TimeUnit.SECONDS); }

    /** 注解声明算力性质，后台实际授权由显式开关、系统受理事实和执行围栏落实。 */
    @RequireRole(UserRoleEnum.INSIDER)
    public void tick() {
        if (!enabled || timer.isShutdown()) return;
        try { if (store.scheduleDue() != null) runner.wake(); }
        catch (RuntimeException failure) { LOGGER.warn("Trend daily admission unavailable; no external work started by scheduler"); }
    }

    /** 停止检测不把已发出的外部请求当成未计费，任务由原执行器/账本管理。 */
    @PreDestroy
    public void close() { timer.shutdownNow(); }
}
