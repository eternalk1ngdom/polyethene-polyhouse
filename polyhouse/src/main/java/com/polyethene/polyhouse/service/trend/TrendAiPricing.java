package com.polyethene.polyhouse.service.trend;

import com.fasterxml.jackson.databind.JsonNode;
import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.exception.BusinessException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;

/**
 * 星潮 AI 缓存命中解析与参考平台实扣估算工具（无状态纯函数）。
 * 仅用于屋主双口径对账展示，绝不参与 10 元月预算安全硬闸门判断。
 */
public final class TrendAiPricing {
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    /** 非高峰时段折扣系数（DeepSeek 空闲时段 5 折）。 */
    private static final BigDecimal OFF_PEAK_DISCOUNT = new BigDecimal("0.5");
    /** 缓存命中相对未命中输入的单价比例（高峰 0.04 / 2.00 = 0.02；空闲折后 0.02 / 1.00 = 0.02）。 */
    private static final BigDecimal CACHE_HIT_RATIO = new BigDecimal("0.02");
    private static final int MAX_TOKENS = 100_000;

    private TrendAiPricing() { }

    /**
     * 从上游 usage 对象解析可选的缓存命中/未命中 token；
     * 两者均缺失时安全回退 (0, promptTokens)；任一越界或总和与 promptTokens 不符则拒绝。
     */
    public static CacheTokens parseCacheTokens(JsonNode usage, int promptTokens) {
        if (promptTokens < 0 || promptTokens > MAX_TOKENS) throw invalid();
        JsonNode hitNode = usage == null ? null : usage.get("prompt_cache_hit_tokens");
        JsonNode missNode = usage == null ? null : usage.get("prompt_cache_miss_tokens");
        boolean hasHit = hitNode != null && !hitNode.isNull();
        boolean hasMiss = missNode != null && !missNode.isNull();
        if (!hasHit && !hasMiss) {
            return new CacheTokens(0, promptTokens);
        }
        int hit = hasHit ? parseOptionalToken(hitNode) : -1;
        int miss = hasMiss ? parseOptionalToken(missNode) : -1;
        if (hasHit && hasMiss) {
            if ((long) hit + miss != promptTokens) throw invalid();
            return new CacheTokens(hit, miss);
        }
        if (hasHit) {
            if (hit > promptTokens) throw invalid();
            return new CacheTokens(hit, promptTokens - hit);
        }
        if (miss > promptTokens) throw invalid();
        return new CacheTokens(promptTokens - miss, miss);
    }

    /**
     * 判断给定 UTC 时刻是否处于北京时间非高峰 5 折时段：周末全天，或工作日 00:30（含）～ 08:30（不含）。
     * 明确不含法定调休节假日，前端与文档须同步注明。
     */
    public static boolean isOffPeak(Instant timestamp) {
        ZonedDateTime beijing = (timestamp == null ? Instant.EPOCH : timestamp).atZone(BEIJING);
        DayOfWeek day = beijing.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return true;
        }
        int minuteOfDay = beijing.getHour() * 60 + beijing.getMinute();
        return minuteOfDay >= 30 && minuteOfDay < 8 * 60 + 30;
    }

    /**
     * 按缓存命中单价与北京时间工作日高峰/非高峰折扣估算参考平台实扣微元（向上取整，且不超过保守扣费）。
     */
    public static long estimateActualMicros(int cacheHit, int cacheMiss, int output,
                                            BigDecimal inputRate, BigDecimal outputRate,
                                            Instant settledAt, long conservativeMicros) {
        if (cacheHit < 0 || cacheMiss < 0 || output < 0
                || inputRate == null || outputRate == null
                || inputRate.signum() < 0 || outputRate.signum() < 0
                || conservativeMicros < 0) {
            throw invalid();
        }
        BigDecimal multiplier = isOffPeak(settledAt) ? OFF_PEAK_DISCOUNT : BigDecimal.ONE;
        BigDecimal effectiveMissRate = inputRate.multiply(multiplier);
        BigDecimal effectiveHitRate = inputRate.multiply(CACHE_HIT_RATIO).multiply(multiplier);
        BigDecimal effectiveOutputRate = outputRate.multiply(multiplier);

        BigDecimal total = effectiveMissRate.multiply(BigDecimal.valueOf(cacheMiss))
                .add(effectiveHitRate.multiply(BigDecimal.valueOf(cacheHit)))
                .add(effectiveOutputRate.multiply(BigDecimal.valueOf(output)));
        long estimated = total.setScale(0, RoundingMode.CEILING).longValueExact();
        return Math.min(estimated, conservativeMicros);
    }

    private static int parseOptionalToken(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) throw invalid();
        int value = node.intValue();
        if (value < 0 || value > MAX_TOKENS) throw invalid();
        return value;
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.INVALID_PARAMETER);
    }

    /**
     * 解析后的输入缓存命中拆分。
     * @param hit 命中缓存的输入 token 数
     * @param miss 未命中缓存的输入 token 数
     */
    public record CacheTokens(int hit, int miss) { }
}
