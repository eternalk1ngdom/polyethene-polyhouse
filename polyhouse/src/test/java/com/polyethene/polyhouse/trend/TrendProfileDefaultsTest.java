package com.polyethene.polyhouse.trend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 直接加载项目的 Spring 配置，确保本机默认值和生产开关各自生效。 */
class TrendProfileDefaultsTest {
    @Test
    void ordinaryLocalRunEnablesReadingAndManualUpdatesOnly() {
        var environment = configuration(null, Map.of());
        assertThat(environment.getActiveProfiles()).isEmpty();
        assertFlag(environment, "enabled", true);
        assertFlag(environment, "jobs.enabled", true);
        assertFlag(environment, "ai.enabled", false);
        assertFlag(environment, "schedule.enabled", false);
        assertFlag(environment, "cache.enabled", false);
    }

    @Test
    void productionStillRequiresExplicitActivation() {
        var environment = configuration("prod", Map.of());
        assertThat(environment.getProperty("server.address")).isEqualTo("0.0.0.0");
        assertFlag(environment, "enabled", false);
        assertFlag(environment, "jobs.enabled", false);
        assertFlag(environment, "ai.enabled", false);
        assertFlag(environment, "schedule.enabled", false);
    }

    @Test
    void explicitSettingsOverrideTheProfileDefaults() {
        var local = configuration(null, Map.of("POLYHOUSE_TREND_ENABLED", "false",
                "POLYHOUSE_TREND_JOBS_ENABLED", "false"));
        assertFlag(local, "enabled", false);
        assertFlag(local, "jobs.enabled", false);

        var production = configuration("prod", Map.of("POLYHOUSE_TREND_ENABLED", "true",
                "POLYHOUSE_TREND_JOBS_ENABLED", "true"));
        assertFlag(production, "enabled", true);
        assertFlag(production, "jobs.enabled", true);
        assertFlag(production, "ai.enabled", false);
        assertFlag(production, "schedule.enabled", false);
    }

    private static ConfigurableEnvironment configuration(String profile, Map<String, Object> flags) {
        var environment = new StandardEnvironment();
        // 测试只接收本用例的环境变量，不依赖执行机已有的 IDE 或 CI 设置。
        var sources = environment.getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        sources.replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, flags));
        if (profile != null) environment.setActiveProfiles(profile);
        ConfigDataEnvironmentPostProcessor.applyTo(environment);
        return environment;
    }

    private static void assertFlag(ConfigurableEnvironment environment, String flag, boolean enabled) {
        assertThat(environment.getProperty("polyhouse.trend." + flag, Boolean.class)).isEqualTo(enabled);
    }
}
