package com.polyethene.polyhouse.model.dto;

import jakarta.validation.constraints.Pattern;

/**
 * 7 日榜内风向统计查询入参；仅支持基于已发布日榜聚合，不接受任意周期，日历合法性在服务层校验。
 * @param periodType 仅接受 DAILY，避免冒充官方周榜
 * @param periodKey 观察窗口锚点日期（北京时间 YYYY-MM-DD），省略时取最新已发布期号
 */
public record TrendWindowQueryDTO(@Pattern(regexp = "DAILY") String periodType,
                                  @Pattern(regexp = "[0-9]{4}-[0-9]{2}-[0-9]{2}") String periodKey) {
    /** 缺省周期明确为日榜，不对非法非空输入作静默回退。 */
    public TrendWindowQueryDTO {
        periodType = periodType == null ? "DAILY" : periodType;
    }
}
