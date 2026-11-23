package com.polyethene.polyhouse.model.dto;

import jakarta.validation.constraints.Pattern;

/**
 * 屋主按需触发 7 日风口简报生成入参；不提供强制重抽开关，同指纹输入严格零费用复用。
 * @param periodKey 观察窗口锚点日期（北京时间 YYYY-MM-DD），省略时取最新已发布期号
 */
public record TrendBriefingGenerateDTO(@Pattern(regexp = "[0-9]{4}-[0-9]{2}-[0-9]{2}") String periodKey) { }
