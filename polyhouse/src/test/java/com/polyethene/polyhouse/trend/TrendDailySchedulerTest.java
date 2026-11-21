package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.config.*;
import com.polyethene.polyhouse.service.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TrendDailySchedulerTest {
    @Test void disabledOrInvalidConfigurationDoesNotEvenQueryDatabase() {
        var store = mock(TrendJobStore.class); var runner = mock(TrendJobRunner.class);
        for (var settings : new TrendScheduleProperties[] {new TrendScheduleProperties(false, null, null),
                new TrendScheduleProperties(true, "bad", 45), new TrendScheduleProperties(true, "23:59", 45),
                new TrendScheduleProperties(true, "06:30", 5)}) {
            var scheduler = new TrendDailyScheduler(store, runner, new TrendProperties(true, null), new TrendJobProperties(true), settings);
            scheduler.start(); scheduler.tick(); scheduler.close();
        }
        verifyNoInteractions(store, runner);
    }

    @Test void defaultBeijingTimeIsSixThirtyWithOneFortyFiveMinuteCompensation() {
        var settings = new TrendScheduleProperties(true, null, null);
        assertThat(settings.ready()).isTrue(); assertThat(settings.startMinute()).isEqualTo(390); assertThat(settings.retryDelayMinutes()).isEqualTo(45);
    }

    @Test void admissionFailureCannotWakeWorkerOrEscapeMaintenanceLoop() {
        var store = mock(TrendJobStore.class); var runner = mock(TrendJobRunner.class);
        when(store.scheduleDue()).thenThrow(new IllegalStateException("private diagnostic"));
        var scheduler = new TrendDailyScheduler(store, runner, new TrendProperties(true, null), new TrendJobProperties(true), new TrendScheduleProperties(true, null, null));
        try { scheduler.tick(); verifyNoInteractions(runner); }
        finally { scheduler.close(); }
    }
}
