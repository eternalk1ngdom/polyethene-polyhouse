package com.polyethene.polyhouse.model.entity;

import java.time.LocalDateTime;

/**
 * 数据库任务快照，只在内部流转；身份版本用于执行前复核，不保存会话令牌。
 * @param id 请求和任务共用的幂等 UUID
 * @param actorId 受理时的屋主编号
 * @param credentialVersion 受理时的凭据版本，改密/重置后旧任务不能继续付费
 * @param aiRequested 受理时冻结是否允许分析，后续打开开关不扩大原请求意图
 * @param generation 全局单调代数，旧执行不能释放新槽或发布
 * @param state 执行状态，不与是否发布或解读完整度混用
 * @param phase 最近进入的阶段，失败时保留故障阶段
 * @param createdAt UTC 微秒受理时间
 * @param startedAt UTC 微秒开始时间
 * @param completedAt UTC 微秒结束时间
 * @param leaseUntil 当前执行权期限，过期运行不可续命
 * @param deadline 从受理起最多三十分钟
 * @param reportId 仅成功发布后记录的版本编号
 * @param reason 固定安全原因码，无上游原文
 */
public record TrendJobRecord(String id, long actorId, long credentialVersion, boolean aiRequested,
                             long generation, String state, String phase, LocalDateTime createdAt,
                             LocalDateTime startedAt, LocalDateTime completedAt, LocalDateTime leaseUntil,
                             LocalDateTime deadline, String reportId, String reason) { }
