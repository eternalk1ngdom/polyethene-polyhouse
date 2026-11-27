package com.polyethene.polyhouse.model.entity;

import java.time.LocalDate;

/**
 * 已发布星潮日报的全局日期边界投影，仅在仓储与服务层内部流转。
 * @param earliestDate 最早有发布记录的日期，无记录时为 null
 * @param latestDate 最新有发布记录的日期，无记录时为 null
 */
public record TrendPeriodBounds(LocalDate earliestDate, LocalDate latestDate) {}
