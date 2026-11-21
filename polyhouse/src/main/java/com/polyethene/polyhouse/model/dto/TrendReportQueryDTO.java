package com.polyethene.polyhouse.model.dto;

import jakarta.validation.constraints.Pattern;

/**
 * 日报查询不接受任意周期或任意资源 URL；日期的日历有效性在服务层严格校验。
 * @param periodType 首批只接受 DAILY，避免伪装提供周/月榜
 * @param periodKey 北京时间期号，省略表示最近已发布日期
 */
public record TrendReportQueryDTO(@Pattern(regexp = "DAILY") String periodType,
                                  @Pattern(regexp = "[0-9]{4}-[0-9]{2}-[0-9]{2}") String periodKey) {
    /** 缺省周期明确为日榜，不对非法非空输入作回退。 */
    public TrendReportQueryDTO {
        periodType = periodType == null ? "DAILY" : periodType;
    }
}
