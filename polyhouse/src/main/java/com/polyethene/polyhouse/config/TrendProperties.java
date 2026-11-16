package com.polyethene.polyhouse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 模块默认关闭，既有启动无需外部令牌；显式启用前先完成独立迁移。 */
@ConfigurationProperties("polyhouse.trend")
public record TrendProperties(boolean enabled, String githubToken) {
    /** 配置对象可能被框架记录，禁止自动生成的 record 文本暴露令牌。 */
    @Override
    public String toString() {
        return "TrendProperties[enabled=" + enabled + "]";
    }
}
