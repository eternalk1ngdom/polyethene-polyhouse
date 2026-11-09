package com.polyethene.polyhouse.config;

import java.time.Duration;
import java.util.function.LongSupplier;

/** 固定大小的单实例令牌桶及并发上限，无请求键、外部访问或等待队列。 */
final class ApiRequestBudget {

    private static final long NANOS_PER_SECOND = Duration.ofSeconds(1).toNanos();

    private final LongSupplier clock;
    private final long interval;
    private final long capacity;
    private final int maxConcurrent;
    private long credit;
    private long lastRefill;
    private int active;

    ApiRequestBudget(ApiLimitProperties.Budget settings, LongSupplier clock) {
        this.clock = clock;
        interval = Duration.ofMinutes(1).toNanos() / settings.requestsPerMinute();
        capacity = interval * settings.burst();
        maxConcurrent = settings.concurrent();
        credit = capacity;
        lastRefill = clock.getAsLong();
    }

    /** 将刷新、扣额和获取并发位置合为一次短临界区，返回 0 表示已获准。 */
    synchronized long enterOrRetryAfter() {
        long now = clock.getAsLong();
        credit = Math.min(capacity, credit + Math.min(capacity, now - lastRefill));
        lastRefill = now;
        long waitNanos = interval - credit;
        if (active == maxConcurrent || waitNanos > 0) {
            // 并发释放时间不可预测，至少建议等待一秒，客户端下一次仍须重新竞争。
            return Math.max(1, (waitNanos + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND);
        }
        credit -= interval;
        active++;
        return 0;
    }

    synchronized void leave() {
        active--;
    }
}
