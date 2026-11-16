package com.polyethene.polyhouse.model.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 每日期每次尝试只有一个受理事实，任务清理时级联删除，不形成另一份永久任务账本。
 * @param periodDate 原受理北京日期
 * @param attempt 0首次、1补偿，数据库约束最多两次
 * @param jobId 对应唯一任务，身份必须为空且开关启用才能执行
 * @param createdAt 原受理UTC时间，与任务一致
 */
public record TrendScheduleRecord(LocalDate periodDate, int attempt, String jobId, LocalDateTime createdAt) { }
