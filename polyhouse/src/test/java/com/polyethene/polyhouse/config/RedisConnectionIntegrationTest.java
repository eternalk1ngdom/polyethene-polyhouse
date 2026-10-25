package com.polyethene.polyhouse.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 显式启用的 Redis 实例验证；普通测试不要求 Redis 在线，集成验证不加载 MySQL 或 Web 组件。
 */
@EnabledIfSystemProperty(named = "polyhouse.redis.integration", matches = "true")
@SpringJUnitConfig(classes = RedisConnectionIntegrationTest.RedisTestConfiguration.class,
        initializers = ConfigDataApplicationContextInitializer.class)
class RedisConnectionIntegrationTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisConnectionIntegrationTest.class);
    private static final Duration TEST_KEY_TTL = Duration.ofSeconds(60);

    @Autowired
    private LettuceConnectionFactory connectionFactory;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void shouldPingRedisUsingApplicationConfiguration() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            assertThat(connection.ping()).isEqualTo("PONG");
        }
        LOGGER.info("Redis connection verified: host={}, port={}, database={}",
                connectionFactory.getHostName(), connectionFactory.getPort(), connectionFactory.getDatabase());
    }

    @Test
    void shouldRoundTripReadableStringsWithExpiry() {
        String key = newTestKey();
        boolean created = false;
        try {
            // SET NX EX 同时限制唯一性和生命周期，异常退出时也只留下短期测试数据。
            created = Boolean.TRUE.equals(redisTemplate.opsForValue()
                    .setIfAbsent(key, "连接验证通过", TEST_KEY_TTL));
            assertThat(created).isTrue();
            assertThat(stringRedisTemplate.opsForValue().get(key)).isEqualTo("连接验证通过");
            assertThat(redisTemplate.getExpire(key, TimeUnit.SECONDS))
                    .isPositive().isLessThanOrEqualTo(TEST_KEY_TTL.toSeconds());
        } finally {
            if (created) {
                redisTemplate.delete(key);
            }
        }
        assertThat(stringRedisTemplate.hasKey(key)).isFalse();
    }

    @Test
    void shouldIncrementTextCountersWithoutLosingExpiry() {
        String key = newTestKey();
        boolean created = false;
        try {
            created = Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, "0", TEST_KEY_TTL));
            assertThat(created).isTrue();
            assertThat(redisTemplate.opsForValue().increment(key)).isEqualTo(1L);
            assertThat(redisTemplate.opsForValue().increment(key)).isEqualTo(2L);
            assertThat(stringRedisTemplate.opsForValue().get(key)).isEqualTo("2");
            assertThat(redisTemplate.getExpire(key, TimeUnit.SECONDS)).isPositive();
        } finally {
            if (created) {
                redisTemplate.delete(key);
            }
        }
        assertThat(stringRedisTemplate.hasKey(key)).isFalse();
    }

    private String newTestKey() {
        return "polyhouse:dev:integration:" + UUID.randomUUID();
    }

    /** 只装载 Redis 自动配置，确保测试读取应用配置且不依赖数据库服务。 */
    @TestConfiguration(proxyBeanMethods = false)
    @ImportAutoConfiguration(RedisAutoConfiguration.class)
    @Import(RedisConfig.class)
    static class RedisTestConfiguration {
    }
}
