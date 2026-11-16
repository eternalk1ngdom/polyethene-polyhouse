package com.polyethene.polyhouse.model.vo;

import java.time.Instant;

/**
 * 仅屋主可读的任务进度；不返回凭据版本、执行代数、原始错误或材料。
 * @param jobId 后续查询及响应丢失后核对的编号
 * @param state QUEUED/RUNNING/SUCCEEDED/PARTIAL/FAILED/INTERRUPTED
 * @param phase 最近进入的工作阶段
 * @param aiRequested 本次任务是否允许付费分析
 * @param createdAt 服务端实际受理时间
 * @param startedAt 实际开始时间，排队时为空
 * @param completedAt 终结时间，活动任务为空
 * @param deadline 整体执行上限
 * @param reportId 已发布版本，失败为空
 * @param reason 固定安全原因码，失败时用于排查
 */
public record TrendJobVO(String jobId, String state, String phase, boolean aiRequested, Instant createdAt,
                         Instant startedAt, Instant completedAt, Instant deadline, String reportId, String reason) { }
