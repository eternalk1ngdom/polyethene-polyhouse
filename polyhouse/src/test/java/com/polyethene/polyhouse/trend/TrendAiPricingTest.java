package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.config.TrendAiProperties;
import com.polyethene.polyhouse.mapper.TrendAiMapper;
import com.polyethene.polyhouse.model.entity.TrendAiRepository;
import com.polyethene.polyhouse.model.entity.TrendAiRequest;
import com.polyethene.polyhouse.service.TrendAiLedger;
import com.polyethene.polyhouse.service.trend.TrendAiPricing;
import com.polyethene.polyhouse.service.trend.TrendBriefingCodec;
import com.polyethene.polyhouse.service.trend.TrendInsightCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖闭环 3 核心契约：缓存命中字段解析与越界拦截、高峰/空闲时段参考实扣估算，以及 10 元保守安全闸门不受优惠影响。
 */
class TrendAiPricingTest {
    private static final TrendInsightCodec INSIGHT_CODEC = new TrendInsightCodec(TrendFixture.json());
    private static final TrendBriefingCodec BRIEFING_CODEC = new TrendBriefingCodec(TrendFixture.json());

    @ParameterizedTest(name = "合法缓存命中字段解析：{0}")
    @MethodSource("validCacheEnvelopeCases")
    void parsesOptionalCacheTokensInBothCodecs(String scenario, String usageExtra, int expectedHit, int expectedMiss) {
        String body = """
                {
                  "usage": {"prompt_tokens": 1000, "completion_tokens": 200, "total_tokens": 1200%s},
                  "choices": [{"finish_reason": "stop", "message": {"role": "assistant", "content": "{}"}}]
                }
                """.formatted(usageExtra);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        var insightEnv = INSIGHT_CODEC.envelope(bytes);
        var briefingEnv = BRIEFING_CODEC.envelope(bytes);

        assertThat(insightEnv.cacheHit()).isEqualTo(expectedHit);
        assertThat(insightEnv.cacheMiss()).isEqualTo(expectedMiss);
        assertThat(briefingEnv.cacheHit()).isEqualTo(expectedHit);
        assertThat(briefingEnv.cacheMiss()).isEqualTo(expectedMiss);
    }

    static Stream<Arguments> validCacheEnvelopeCases() {
        return Stream.of(
                Arguments.of("缺省缓存字段回退全未命中", "", 0, 1000),
                Arguments.of("同时提供 hit 与 miss 且之和等于 prompt_tokens", ",\"prompt_cache_hit_tokens\":700,\"prompt_cache_miss_tokens\":300", 700, 300),
                Arguments.of("仅提供 hit 自动推导 miss", ",\"prompt_cache_hit_tokens\":850", 850, 150),
                Arguments.of("仅提供 miss 自动推导 hit", ",\"prompt_cache_miss_tokens\":250", 750, 250),
                Arguments.of("全部命中缓存", ",\"prompt_cache_hit_tokens\":1000,\"prompt_cache_miss_tokens\":0", 1000, 0)
        );
    }

    @ParameterizedTest(name = "拦截非法缓存命中字段：{0}")
    @MethodSource("invalidCacheEnvelopeCases")
    void rejectsOutOfBoundsOrInconsistentCacheTokens(String scenario, String usageExtra) {
        String body = """
                {
                  "usage": {"prompt_tokens": 1000, "completion_tokens": 200, "total_tokens": 1200%s},
                  "choices": [{"finish_reason": "stop", "message": {"role": "assistant", "content": "{}"}}]
                }
                """.formatted(usageExtra);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> INSIGHT_CODEC.envelope(bytes)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> BRIEFING_CODEC.envelope(bytes)).isInstanceOf(BusinessException.class);
    }

    static Stream<Arguments> invalidCacheEnvelopeCases() {
        return Stream.of(
                Arguments.of("hit + miss 不等于 prompt_tokens", ",\"prompt_cache_hit_tokens\":700,\"prompt_cache_miss_tokens\":200"),
                Arguments.of("hit 超过 prompt_tokens", ",\"prompt_cache_hit_tokens\":1200"),
                Arguments.of("hit 为负数", ",\"prompt_cache_hit_tokens\":-1"),
                Arguments.of("hit 为小数字符串", ",\"prompt_cache_hit_tokens\":\"100\"")
        );
    }

    @ParameterizedTest(name = "北京时间高峰/空闲时段参考实扣估算：{0}")
    @MethodSource("pricingCases")
    void estimatesPlatformActualCostByCacheAndTimeWindow(
            String scenario, Instant utcTime, boolean expectedOffPeak,
            int hit, int miss, int output, long expectedEstimatedMicros) {
        BigDecimal inRate = BigDecimal.valueOf(2);
        BigDecimal outRate = BigDecimal.valueOf(8);
        long conservative = TrendAiLedger.cost(hit + miss, output, inRate, outRate);

        assertThat(TrendAiPricing.isOffPeak(utcTime)).isEqualTo(expectedOffPeak);
        long estimated = TrendAiPricing.estimateActualMicros(hit, miss, output, inRate, outRate, utcTime, conservative);
        assertThat(estimated).isEqualTo(expectedEstimatedMicros);
        assertThat(estimated).isLessThanOrEqualTo(conservative);
    }

    static Stream<Arguments> pricingCases() {
        // 2026-11-26 为周四（工作日）
        Instant thuPeakMorning = Instant.parse("2026-11-26T00:30:00Z"); // 北京 08:30（高峰起点）
        Instant thuOffPeakMorning = Instant.parse("2026-11-26T00:29:00Z"); // 北京 08:29（空闲）
        Instant thuOffPeakMidnight = Instant.parse("2026-11-25T16:30:00Z"); // 北京 00:30（空闲起点）
        Instant thuPeakLateNight = Instant.parse("2026-11-25T16:29:00Z"); // 北京 00:29（高峰）
        // 2026-11-28 为周六（全天非高峰）
        Instant satNoon = Instant.parse("2026-11-28T04:00:00Z"); // 北京周六 12:00

        return Stream.of(
                // 高峰：miss 300*2=600 + hit 700*0.04=28 + out 200*8=1600 => 2228
                Arguments.of("工作日高峰 08:30 含缓存命中", thuPeakMorning, false, 700, 300, 200, 2228L),
                // 空闲 5 折：miss 300*1=300 + hit 700*0.02=14 + out 200*4=800 => 1114
                Arguments.of("工作日空闲 08:29 含缓存命中 5 折", thuOffPeakMorning, true, 700, 300, 200, 1114L),
                Arguments.of("工作日空闲 00:30 边界 5 折", thuOffPeakMidnight, true, 700, 300, 200, 1114L),
                Arguments.of("工作日高峰 00:29 边界全价", thuPeakLateNight, false, 700, 300, 200, 2228L),
                Arguments.of("周六午间全天空闲 5 折", satNoon, true, 700, 300, 200, 1114L)
        );
    }

    @Test
    void conservativeBudgetGateIsUnaffectedByCacheOrOffPeakDiscounts() {
        var mapper = mock(TrendAiMapper.class);
        var txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var settings = new TrendAiProperties(true, "sk-test-1234567890", "deepseek-flash",
                BigDecimal.valueOf(2), BigDecimal.valueOf(8), 10_000_000L, false, "low");
        // 北京时间 06:30（工作日空闲 5 折时段）
        Instant offPeak = Instant.parse("2026-11-25T22:30:00Z");
        var ledger = new TrendAiLedger(mapper, settings, Clock.fixed(offPeak, ZoneOffset.UTC), txManager);

        String reqId = "11111111-2222-4333-8444-555555555555";
        var request = new TrendAiRequest(reqId, "2026-11", 42L, "a".repeat(40), "deepseek-flash", "radar-4",
                "RESERVED", 20_000L, null, 4000, 1500, BigDecimal.valueOf(2), BigDecimal.valueOf(8));
        when(mapper.request(reqId)).thenReturn(request);
        when(mapper.lockRepository(42L)).thenReturn(new TrendAiRepository(42L, reqId, null));
        when(mapper.lockRequest(reqId)).thenReturn(request);
        when(mapper.settle(eq("2026-11"), eq(20_000L), eq(3600L))).thenReturn(1);
        when(mapper.finish(eq(reqId), eq("SETTLED"), eq(3600L), eq(1000), eq(200), eq("VALID_OUTPUT"))).thenReturn(1);
        when(mapper.release(eq(42L), eq(reqId), eq("{}"))).thenReturn(1);

        ledger.settle(reqId, 1000, 200, 800, 200, "{}");

        // 保守月预算必须严格扣满 1000*2 + 200*8 = 3600 微元，不受缓存与 5 折优惠放松；
        verify(mapper).settle("2026-11", 20_000L, 3600L);
        // 参考平台实扣单独记录：空闲 5 折 + 800 缓存命中 => 200*1 + 800*0.02 + 200*4 = 1016 微元。
        verify(mapper).recordCacheUsage(reqId, 800, 200, 1016L);
    }
}
