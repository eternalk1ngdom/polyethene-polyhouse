package com.polyethene.polyhouse.model.dto;

import jakarta.validation.constraints.*;

/**
 * 费用只按一个月份分页，不提供无界请求明细导出。
 * @param month 省略时为北京当前月，限定数据库可表达的年份
 * @param after 待核对请求UUID排他游标，不是offset
 * @param limit 默认20，最多50项，额外一项只判断是否还有下一页
 */
public record TrendFeeQueryDTO(
        @Pattern(regexp = "[1-9][0-9]{3}-(?:0[1-9]|1[0-2])") String month,
        @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String after,
        @Min(1) @Max(50) Integer limit) {
    /** 默认页长由后端决定，调用者不能借缺省值无限读取。 */
    public TrendFeeQueryDTO { if (limit == null) limit = 20; }
}
