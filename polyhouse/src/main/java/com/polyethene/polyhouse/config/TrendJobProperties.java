package com.polyethene.polyhouse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 与公开阅读和付费开关分开，避免仅配置 Key 就在重启时执行历史任务。
 * @param enabled 明确允许受理和执行后台任务；默认关闭
 */
@ConfigurationProperties("polyhouse.trend.jobs")
public record TrendJobProperties(boolean enabled) { }
