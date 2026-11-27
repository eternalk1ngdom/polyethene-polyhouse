package com.polyethene.polyhouse.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

/**
 * 用日期游标或指定月份读取历史，不向公网提供无界列表或深分页。
 * @param periodType 首批只支持 DAILY
 * @param before 排除该日期及更新日期，便于继续读取更早期数（与 month 互斥）
 * @param limit 最多三十期，默认十四期（仅游标分页模式生效）
 * @param month 可选按月查询（YYYY-MM），单次返回该月全部已发布日期（与 before 互斥）
 */
public record TrendPeriodsQueryDTO(@Pattern(regexp = "DAILY") String periodType,
                                   @Pattern(regexp = "[0-9]{4}-[0-9]{2}-[0-9]{2}") String before,
                                   @Min(1) @Max(30) Integer limit,
                                   @Pattern(regexp = "[0-9]{4}-(0[1-9]|1[0-2])") String month) {
    /** 有界默认值也用于服务内部调用，非空非法值仍拒绝；保留唯一规范构造器以供 Spring @ModelAttribute 绑定。 */
    public TrendPeriodsQueryDTO {
        periodType = periodType == null ? "DAILY" : periodType;
        limit = limit == null ? 14 : limit;
    }
}
