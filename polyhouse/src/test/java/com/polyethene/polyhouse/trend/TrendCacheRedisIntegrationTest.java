package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.config.RedisConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** 真实本机Redis执行生产Lua，只写随机测试键，不接触正式内容键/认证键，不连接数据库。 */
@EnabledIfSystemProperty(named = "polyhouse.trend.redis", matches = "true")
@SpringJUnitConfig(classes = TrendCacheRedisIntegrationTest.RedisOnly.class, initializers = ConfigDataApplicationContextInitializer.class)
class TrendCacheRedisIntegrationTest {
    private static final String OLD = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
    private static final String NEW = "11111111-2222-4333-8444-555555555555";
    private static final String EARLY = "20261116000000000001";
    private static final String LATE = "20261116000000000002";
    @Autowired private StringRedisTemplate redis;
    @Autowired private LettuceConnectionFactory factory;
    private final List<String> owned = new ArrayList<>();
    private final DefaultRedisScript<Long> write = script("redis/trend_write_view.lua", Long.class);
    private final DefaultRedisScript<String> read = script("redis/trend_read_view.lua", String.class);

    @AfterEach void cleanOnlyOwnedKeys() {
        for (String key : owned) {
            if (!key.matches("polyhouse:trend:test-cache:[0-9a-f-]{36}")) throw new IllegalStateException("Invalid test cache key");
            redis.delete(key);
        }
    }

    @Test void concurrentOldAndNewMicrosecondsCannotOverwriteLatestAndTtlIsBounded() throws Exception {
        String sequential = key();
        assertThat(redis.execute(write, List.of(sequential), OLD, EARLY, "old")).isEqualTo(1L);
        assertThat(redis.execute(write, List.of(sequential), NEW, LATE, "new")).isEqualTo(1L);
        assertThat(redis.execute(read, List.of(sequential), NEW, LATE)).isEqualTo("new");
        String key = key();
        try (var workers = Executors.newFixedThreadPool(4)) {
            List<Future<Long>> results = new ArrayList<>();
            for (int index = 0; index < 12; index++) {
                boolean newer = index % 2 == 0;
                results.add(workers.submit(() -> redis.execute(write, List.of(key), newer ? NEW : OLD, newer ? LATE : EARLY, newer ? "new" : "old")));
            }
            for (var result : results) assertThat(result.get(5, TimeUnit.SECONDS)).isIn(0L, 1L);
        }
        assertThat(redis.execute(read, List.of(key), NEW, LATE)).isEqualTo("new");
        assertThat(redis.execute(read, List.of(key), OLD, EARLY)).isNull();
        assertThat(redis.execute(write, List.of(key), OLD, LATE, "same stamp different id")).isZero();
        assertThat(redis.opsForHash().size(key)).isEqualTo(3); assertThat(redis.getExpire(key)).isBetween(1L, 900L);
    }

    @Test void corruptOversizedAndWrongTypedValuesDoNotBecomeCacheHits() {
        String key = key();
        redis.opsForValue().set(key, "own wrong type fixture", Duration.ofSeconds(60));
        assertThat(redis.execute(read, List.of(key), NEW, LATE)).isNull();
        assertThat(redis.execute(write, List.of(key), NEW, LATE, "payload")).isZero();
        redis.delete(key);
        assertThat(redis.execute(write, List.of(key), NEW, LATE, "payload")).isEqualTo(1L);
        redis.opsForHash().put(key, "payload", "x".repeat(1024 * 1024 + 1));
        assertThat(redis.execute(read, List.of(key), NEW, LATE)).isNull();
        assertThat(redis.execute(write, List.of(key), NEW, LATE, "repaired")).isEqualTo(1L);
        assertThat(redis.execute(read, List.of(key), NEW, LATE)).isEqualTo("repaired");
        redis.opsForHash().put(key, "stamp", "x".repeat(1024 * 1024 + 1));
        assertThat(redis.execute(read, List.of(key), NEW, LATE)).isNull();
        assertThat(redis.execute(write, List.of(key), NEW, LATE, "repaired stamp")).isEqualTo(1L);
        assertThat(redis.execute(read, List.of(key), NEW, LATE)).isEqualTo("repaired stamp");
    }

    @Test void writerRejectsUnboundedOrMalformedArgumentsAndReadsDoNotExtendTtl() {
        String key = key();
        assertThat(redis.execute(write, List.of(key), NEW, "bad", "payload")).isZero();
        assertThat(redis.execute(write, List.of(key), NEW, LATE, "x".repeat(1024 * 1024 + 1))).isZero();
        assertThat(redis.hasKey(key)).isFalse();
        redis.execute(write, List.of(key), NEW, LATE, "payload"); redis.expire(key, Duration.ofSeconds(5));
        assertThat(redis.execute(read, List.of(key), NEW, LATE)).isEqualTo("payload"); assertThat(redis.getExpire(key)).isBetween(1L, 5L);
    }

    private String key() {
        if (!List.of("localhost", "127.0.0.1", "::1").contains(factory.getHostName())) throw new IllegalStateException("Only local Redis allowed");
        String key = "polyhouse:trend:test-cache:" + UUID.randomUUID();
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, "owned fixture", Duration.ofSeconds(60)))) throw new IllegalStateException("Test key collision");
        owned.add(key); redis.delete(key); return key;
    }
    private static <T> DefaultRedisScript<T> script(String path, Class<T> type) {
        var script = new DefaultRedisScript<T>(); script.setLocation(new ClassPathResource(path)); script.setResultType(type); return script;
    }
    /** 仅装载Redis，不启动网站、任务定时器、MySQL或真实AI。 */
    @TestConfiguration(proxyBeanMethods = false)
    @ImportAutoConfiguration(RedisAutoConfiguration.class)
    @Import(RedisConfig.class)
    static class RedisOnly { }
}
