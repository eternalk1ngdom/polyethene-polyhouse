package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.config.*;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.mapper.TrendMapper;
import com.polyethene.polyhouse.model.dto.TrendReportQueryDTO;
import com.polyethene.polyhouse.model.entity.TrendReportRecord;
import com.polyethene.polyhouse.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.dao.DataAccessResourceFailureException;
import java.time.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 缓存命中仍需权威头，旧值/损坏/断连均不会变成公开假报告。 */
class TrendContentCacheTest {
    @Test void disabledCacheNeverTouchesRedis() {
        var redis = mock(StringRedisTemplate.class);
        var cache = new TrendReportCache(redis, new TrendCacheProperties(false), Clock.systemUTC());
        cache.read(record()); cache.write(record()); verifyNoInteractions(redis);
    }

    @Test void cacheFailureFallsBackWithoutRepeatedWriteAndRecoversAfterCooldown() {
        var redis = mock(StringRedisTemplate.class); var clock = mock(Clock.class);
        var now = new AtomicReference<>(TrendAiFixture.NOW); when(clock.instant()).thenAnswer(invocation -> now.get());
        var cache = new TrendReportCache(redis, new TrendCacheProperties(true), clock);
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<String>>any(), anyList(), anyString(), anyString()))
                .thenThrow(new DataAccessResourceFailureException("private redis diagnostic")).thenReturn(record().payload());
        assertThat(cache.read(record())).isNull(); cache.write(record()); assertThat(cache.read(record())).isNull();
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

    @Test void staleWrongIdentityOrMalformedPayloadIsReplacedFromImmutableDatabaseVersion() {
        for (String value : new String[] {"{}", record().payload().replace(record().id(), "11111111-2222-4333-8444-555555555555")}) {
            var mapper = mock(TrendMapper.class); var cache = mock(TrendReportCache.class); var record = record();
            when(cache.enabled()).thenReturn(true); when(mapper.latestHeader()).thenReturn(header(record));
            when(cache.read(any())).thenReturn(value); when(mapper.findVersion(record.id())).thenReturn(record);
            var service = new TrendReportService(mapper, TrendFixture.json(), new TrendProperties(true, null), cache);
            assertThat(service.report(new TrendReportQueryDTO(null, null)).reportId()).isEqualTo(record.id());
            verify(cache).write(record);
        }
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
        var redis = mock(StringRedisTemplate.class); var cache = new TrendReportCache(redis, new TrendCacheProperties(true), Clock.systemUTC());
        var r = record(); cache.write(new TrendReportRecord(r.id(), r.periodDate(), r.capturedAt(), "x".repeat(256 * 1024 + 1)));
        verifyNoInteractions(redis);
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
