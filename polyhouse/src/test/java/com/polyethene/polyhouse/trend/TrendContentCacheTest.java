package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.config.*;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.mapper.TrendMapper;
import com.polyethene.polyhouse.model.dto.TrendReportQueryDTO;
import com.polyethene.polyhouse.model.entity.TrendReportRecord;
import com.polyethene.polyhouse.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.dao.DataAccessResourceFailureException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 缓存命中仍需权威头，旧值/损坏/断连均不会变成公开假报告。 */
@ExtendWith(OutputCaptureExtension.class)
class TrendContentCacheTest {
    private static final Clock FIXED_CLOCK = Clock.fixed(TrendAiFixture.NOW, ZoneOffset.UTC);

    @Test void disabledCacheNeverTouchesRedis() {
        var redis = mock(StringRedisTemplate.class);
        var cache = new TrendReportCache(redis, new TrendCacheProperties(false), FIXED_CLOCK);
        cache.read(record()); cache.write(record()); verifyNoInteractions(redis);
    }

    @Test void cacheFailureFallsBackWithoutRepeatedWriteAndRecoversAfterCooldown(CapturedOutput output) {
        var redis = mock(StringRedisTemplate.class); var clock = mock(Clock.class);
        var now = new AtomicReference<>(TrendAiFixture.NOW); when(clock.instant()).thenAnswer(invocation -> now.get());
        var cache = new TrendReportCache(redis, new TrendCacheProperties(true), clock);
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<String>>any(), anyList(), anyString(), anyString()))
                .thenThrow(new DataAccessResourceFailureException("private redis diagnostic")).thenReturn(record().payload());
        assertThat(cache.read(record())).isNull(); cache.write(record()); assertThat(cache.read(record())).isNull();
        // 降级告警必须记录异常简单类名以区分脚本缺失与连接故障，且不能泄露连接诊断细节。
        assertThat(output.getOut()).contains("DataAccessResourceFailureException").doesNotContain("private redis diagnostic");
        verify(redis, times(1)).execute(org.mockito.ArgumentMatchers.<RedisScript<String>>any(), anyList(), anyString(), anyString());
        verify(redis, never()).execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(), anyList(), anyString(), anyString(), anyString());
        now.set(now.get().plusSeconds(31)); assertThat(cache.read(record())).isEqualTo(record().payload());
    }

    @Test void cacheHitAvoidsLargeDatabasePayloadAndHistoricalReadsAlsoCheckPointer() {
        var mapper = mock(TrendMapper.class); var cache = mock(TrendReportCache.class); var record = record();
        when(cache.enabled()).thenReturn(true); when(mapper.latestHeader()).thenReturn(header(record));
        when(mapper.publishedHeader(record.periodDate())).thenReturn(header(record)); when(cache.read(any())).thenReturn(record.payload());
        var service = new TrendReportService(mapper, TrendFixture.json(), new TrendProperties(true, null), cache);
        assertThat(service.report(new TrendReportQueryDTO(null, null)).reportId()).isEqualTo(record.id());
        assertThat(service.report(new TrendReportQueryDTO(null, record.periodDate().toString())).reportId()).isEqualTo(record.id());
        verify(mapper, never()).findVersion(anyString()); verify(mapper, never()).latest();
    }

    @ParameterizedTest(name = "损坏或身份错配缓存载荷回源修复：{0}")
    @MethodSource("corruptCachedPayloads")
    void staleWrongIdentityOrMalformedPayloadIsReplacedFromImmutableDatabaseVersion(String label, String cachedPayload) {
        var mapper = mock(TrendMapper.class); var cache = mock(TrendReportCache.class); var record = record();
        when(cache.enabled()).thenReturn(true); when(mapper.latestHeader()).thenReturn(header(record));
        when(cache.read(any())).thenReturn(cachedPayload); when(mapper.findVersion(record.id())).thenReturn(record);
        var service = new TrendReportService(mapper, TrendFixture.json(), new TrendProperties(true, null), cache);
        assertThat(service.report(new TrendReportQueryDTO(null, null)).reportId()).isEqualTo(record.id());
        verify(cache).write(record);
    }

    private static Stream<Arguments> corruptCachedPayloads() {
        return Stream.of(
                Arguments.of("空 JSON 对象", "{}"),
                Arguments.of("报告 ID 与权威头不一致", record().payload().replace(record().id(), "11111111-2222-4333-8444-555555555555"))
        );
    }

    @Test void unavailableAuthoritativePointerCannotBeMaskedByCacheAndInvalidDatabaseIsNotCached() {
        var mapper = mock(TrendMapper.class); var cache = mock(TrendReportCache.class); when(cache.enabled()).thenReturn(true);
        var service = new TrendReportService(mapper, TrendFixture.json(), new TrendProperties(true, null), cache);
        when(mapper.latestHeader()).thenThrow(new DataAccessResourceFailureException("private"));
        assertThatThrownBy(() -> service.report(new TrendReportQueryDTO(null, null))).isInstanceOf(BusinessException.class);
        verify(cache, never()).read(any());
        doReturn(header(record())).when(mapper).latestHeader(); when(mapper.findVersion(record().id())).thenReturn(new TrendReportRecord(record().id(), record().periodDate(), record().capturedAt(), "{}"));
        assertThatThrownBy(() -> service.report(new TrendReportQueryDTO(null, null))).isInstanceOf(BusinessException.class);
        verify(cache, never()).write(any());
    }

    @Test void payloadBoundPreventsOversizedValuesFromBeingWritten() {
        var redis = mock(StringRedisTemplate.class); var cache = new TrendReportCache(redis, new TrendCacheProperties(true), FIXED_CLOCK);
        var r = record(); cache.write(new TrendReportRecord(r.id(), r.periodDate(), r.capturedAt(), "x".repeat(256 * 1024 + 1)));
        verifyNoInteractions(redis);
    }

    @Test void cacheLuaScriptsExistAndAreWhitelistedInDockerignore() throws Exception {
        for (String name : new String[] {"trend_read_view.lua", "trend_write_view.lua"}) {
            assertThat(new ClassPathResource("redis/" + name).exists()).isTrue();
        }
        Path dockerignore = Path.of("..", ".dockerignore");
        assertThat(Files.exists(dockerignore)).isTrue();
        var lines = Files.readAllLines(dockerignore, StandardCharsets.UTF_8);
        assertThat(lines).contains(
                "!polyhouse/src/main/resources/redis/trend_read_view.lua",
                "!polyhouse/src/main/resources/redis/trend_write_view.lua");
    }

    private static final TrendReportRecord SAVED = saved();
    private static TrendReportRecord record() { return SAVED; }
    private static TrendReportRecord header(TrendReportRecord r) { return new TrendReportRecord(r.id(), r.periodDate(), r.capturedAt(), null); }
    private static TrendReportRecord saved() {
        var view = TrendFixture.report();
        try { return new TrendReportRecord(view.reportId(), view.periodKey(), LocalDateTime.ofInstant(view.capturedAt(), ZoneOffset.UTC), TrendFixture.json().writeValueAsString(view)); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
