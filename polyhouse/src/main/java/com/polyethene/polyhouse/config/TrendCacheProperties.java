package com.polyethene.polyhouse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 内容缓存与认证Redis职责分开，默认不让公开阅读访问Redis。
 * @param enabled 显式启用单条公开内容缓存，故障仍从数据库读取已发布版本
 */
@ConfigurationProperties("polyhouse.trend.cache")
public record TrendCacheProperties(boolean enabled) { }
