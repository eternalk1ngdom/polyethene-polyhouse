package com.polyethene.polyhouse.model.vo;

/**
 * 本月 AI 调用的 Token 级汇总与预计平台实扣估算，供屋主 5 秒对照 DeepSeek 控制台核账。
 * 仅反映本应用已结算请求（不含法定调休节假日）；10 元安全闸门仍严格按原保守高峰口径执行。
 * @param settledRequests 本月已结算请求次数（含单仓与 7 日简报，不含取消与人工保守关闭）
 * @param promptTokens 本月输入 Token 总计
 * @param cacheHitTokens 本月缓存命中 Token 总计
 * @param cacheMissTokens 本月缓存未命中 Token 总计
 * @param completionTokens 本月输出 Token 总计
 * @param estimatedActualMicros 本月预计平台实扣估算微元
 */
public record TrendFeeUsageVO(
        long settledRequests,
        long promptTokens,
        long cacheHitTokens,
        long cacheMissTokens,
        long completionTokens,
        long estimatedActualMicros) {
}
