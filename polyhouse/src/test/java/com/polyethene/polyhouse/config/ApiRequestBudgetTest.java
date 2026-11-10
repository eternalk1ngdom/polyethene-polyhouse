package com.polyethene.polyhouse.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class ApiRequestBudgetTest {

    @Test
    void refillsGraduallyAndKeepsABoundedBurstAfterLongIdle() {
        var now = new AtomicLong();
        var budget = new ApiRequestBudget(new ApiLimitProperties.Budget(30, 2, 1), now::get);
        admitAndFinish(budget);
        admitAndFinish(budget);
        assertThat(budget.enterOrRetryAfter()).isEqualTo(2);

        now.addAndGet(Duration.ofMillis(1_100).toNanos());
        assertThat(budget.enterOrRetryAfter()).isEqualTo(1);
        now.addAndGet(Duration.ofMillis(900).toNanos());
        admitAndFinish(budget);
        assertThat(budget.enterOrRetryAfter()).isEqualTo(2);

        now.addAndGet(Duration.ofHours(1).toNanos());
        admitAndFinish(budget);
        admitAndFinish(budget);
        assertThat(budget.enterOrRetryAfter()).isEqualTo(2);
    }

    @Test
    void aConcurrencyRejectionDoesNotSpendRateBudget() {
        var budget = new ApiRequestBudget(new ApiLimitProperties.Budget(30, 3, 2), () -> 0L);
        assertThat(budget.enterOrRetryAfter()).isZero();
        assertThat(budget.enterOrRetryAfter()).isZero();
        assertThat(budget.enterOrRetryAfter()).isEqualTo(1);

        budget.leave();
        admitAndFinish(budget);
        budget.leave();
        assertThat(budget.enterOrRetryAfter()).isEqualTo(2);
    }

    private static void admitAndFinish(ApiRequestBudget budget) {
        assertThat(budget.enterOrRetryAfter()).isZero();
        budget.leave();
    }
}
