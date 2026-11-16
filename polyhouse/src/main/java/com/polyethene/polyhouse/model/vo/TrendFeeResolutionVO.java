package com.polyethene.polyhouse.model.vo;

import java.time.Instant;

/**
 * 只表示保守预算关闭，不声称获得模型结果、平台扣费证明或退款。
 * @param resolutionId 本次处理事实编号
 * @param requestId 被关闭的原AI请求
 * @param month 仍归原预留月份
 * @param accountedMicros 本笔预留额全额计入的预算
 * @param reason 屋主确认的固定理由
 * @param completedAt 首次成功提交时间，重放不更新
 * @param replayed true表示返回已存在事实，包括主动确认查询
 */
public record TrendFeeResolutionVO(String resolutionId, String requestId, String month, long accountedMicros,
                                    String reason, Instant completedAt, boolean replayed) { }
