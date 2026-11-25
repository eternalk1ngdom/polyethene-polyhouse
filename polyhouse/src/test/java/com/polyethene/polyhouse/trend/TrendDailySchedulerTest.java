package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.config.*;
import com.polyethene.polyhouse.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TrendDailySchedulerTest {
    @ParameterizedTest(name = "忽略停用或非法定时配置：{0}")
    @MethodSource("invalidScheduleSettings")
    void disabledOrInvalidConfigurationDoesNotEvenQueryDatabase(String scenario, TrendScheduleProperties settings) {
        var store = mock(TrendJobStore.class); var runner = mock(TrendJobRunner.class);
        var scheduler = new TrendDailyScheduler(store, runner, new TrendProperties(true, null), new TrendJobProperties(true), settings);
        scheduler.start(); scheduler.tick(); scheduler.close();
        verifyNoInteractions(store, runner);
    }

    private static Stream<Arguments> invalidScheduleSettings() {
        return Stream.of(
                Arguments.of("定时开关关闭", new TrendScheduleProperties(false, null, null)),
                Arguments.of("时间格式非法", new TrendScheduleProperties(true, "bad", 45)),
                Arguments.of("开始时间越界跨日", new TrendScheduleProperties(true, "23:59", 45)),
                Arguments.of("补跑间隔低于下限", new TrendScheduleProperties(true, "06:30", 5)));
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
