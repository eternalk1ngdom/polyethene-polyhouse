package com.polyethene.polyhouse.model.vo;

import java.time.LocalDate;
import java.util.List;

/**
 * 有界的历史期号投影，不泄露被替换的内部版本和执行状态。
 * @param periodType 固定 DAILY
 * @param items 当前可阅读日期，按日期倒序
 * @param nextBefore 有更多记录时继续读取的排他日期游标，无更多记录或按月查询时为 null
 * @param earliestDate 全局最早可阅读日期，无任何发布记录时为 null
 * @param latestDate 全局最新可阅读日期，无任何发布记录时为 null
 */
public record TrendPeriodsVO(String periodType, List<LocalDate> items, LocalDate nextBefore,
                             LocalDate earliestDate, LocalDate latestDate) {
    /** 保留旧三参构造器，向下兼容未填充全局边界的调用与测试。 */
    public TrendPeriodsVO(String periodType, List<LocalDate> items, LocalDate nextBefore) {
        this(periodType, items, nextBefore, null, null);
    }

    /** 游标对应不可变列表，避免序列化期间被调用者改动。 */
    public TrendPeriodsVO {
        items = List.copyOf(items);
    }
}
