package com.polyethene.polyhouse.model.dto;

import jakarta.validation.constraints.Pattern;

/**
 * 7 日风口简报只读查询入参；锚点日期可省略，省略时取最新已发布日报期号。
 * @param periodKey 观察窗口锚点日期（北京时间 YYYY-MM-DD），省略时取最新已发布期号
 */
public record TrendBriefingQueryDTO(@Pattern(regexp = "[0-9]{4}-[0-9]{2}-[0-9]{2}") String periodKey) { }
