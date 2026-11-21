package com.polyethene.polyhouse.model.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 持久版本只追加不原地修改，Controller 只能读取服务转换后的 VO。
 * @param id 幂等发布 UUID
 * @param periodDate 北京日期
 * @param capturedAt UTC 无时区数据库列对应的明确 UTC 时间
 * @param payload 已发布脱敏报告 JSON，不含 README 正文
 */
public record TrendReportRecord(String id, LocalDate periodDate, LocalDateTime capturedAt, String payload) { }
