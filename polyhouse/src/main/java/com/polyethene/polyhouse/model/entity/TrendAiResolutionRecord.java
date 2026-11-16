package com.polyethene.polyhouse.model.entity;

import java.time.LocalDateTime;

/**
 * 保守关闭的不可变审计，处理编号和原请求各自唯一，不把估算写成平台实际账单。
 * @param id 屋主一次处理意图的UUID
 * @param requestId 原AI请求编号
 * @param actorId 受理该操作时的精确屋主身份快照
 * @param monthKey 费用预留的原月份，跨月也不挪到当前月
 * @param accountedMicros 原预留额全部计入已用预算，不退款
 * @param reason 固定枚举理由，不接收可能夹带密钥的自由文本
 * @param completedAt 数据库UTC微秒时间
 */
public record TrendAiResolutionRecord(String id, String requestId, long actorId, String monthKey,
                                       long accountedMicros, String reason, LocalDateTime completedAt) { }
