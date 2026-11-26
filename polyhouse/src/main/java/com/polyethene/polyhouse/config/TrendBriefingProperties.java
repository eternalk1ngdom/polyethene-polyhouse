package com.polyethene.polyhouse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 7 日风口简报自动化配置：独立于每日自动日报开关，默认关闭。
 * @param autoEnabled 每日自动日报发布成功后是否顺路触发一次 7 日风口简报生成，默认 false
 */
@ConfigurationProperties("polyhouse.trend.briefing")
public record TrendBriefingProperties(boolean autoEnabled) {
}
