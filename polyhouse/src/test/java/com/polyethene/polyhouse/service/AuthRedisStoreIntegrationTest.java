package com.polyethene.polyhouse.service;

import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.constant.RedisConstants;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.config.RedisConfig;
import com.polyethene.polyhouse.utils.JwtUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实单机 Redis 原子操作验证，NX 预占随机测试键，只清理确认属于本测试的键。 */
@EnabledIfSystemProperty(named = "polyhouse.redis.integration", matches = "true")
@SpringJUnitConfig(classes = AuthRedisStoreIntegrationTest.RedisTestConfiguration.class,
        initializers = ConfigDataApplicationContextInitializer.class)
class AuthRedisStoreIntegrationTest {

    @Autowired
    private AuthRedisStore store;
    @Autowired
    private StringRedisTemplate redis;

    private final Set<String> ownedKeys = new HashSet<>();
    private long userId;

    @BeforeEach
    void prepareRandomUserId() {
        userId = Math.max(1L, UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE);
    }

    @AfterEach
    void cleanupOwnedKeys() {
        if (!ownedKeys.isEmpty()) {
            redis.delete(ownedKeys);
        }
    }

    @Test
    void shouldKeepClientsSeparateAndPreventOldLogoutFromDeletingNewSession() {
        reserveSession("PC");
        reserveSession("H5");
        var firstPc = claims("PC");
        var secondPc = claims("PC");
        var h5 = claims("H5");
        store.saveSession(firstPc, Duration.ofSeconds(60));
        store.saveSession(h5, Duration.ofSeconds(60));
        store.saveSession(secondPc, Duration.ofSeconds(60));
        assertThat(store.isCurrentSession(firstPc)).isFalse();
        assertThat(store.isCurrentSession(secondPc)).isTrue();
        assertThat(store.isCurrentSession(h5)).isTrue();
        assertThat(redis.opsForValue().get(RedisConstants.buildAuthTokenKey(userId, "PC"))).isEqualTo(secondPc.tokenId());
        assertThat(redis.getExpire(RedisConstants.buildAuthTokenKey(userId, "PC"), TimeUnit.SECONDS))
                .isPositive().isLessThanOrEqualTo(60L);
        store.removeSession(firstPc);
        assertThat(store.isCurrentSession(secondPc)).isTrue();
        store.removeSession(secondPc);
        store.removeSession(secondPc);
        assertThat(store.isCurrentSession(secondPc)).isFalse();
        assertThat(store.isCurrentSession(h5)).isTrue();
    }

    @Test
    void shouldKeepNewSessionDuringConcurrentOldLogout() throws Exception {
        reserveSession("PC");
        var old = claims("PC");
        var replacement = claims("PC");
        store.saveSession(old, Duration.ofSeconds(60));
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var save = executor.submit(() -> {
                start.await();
                store.saveSession(replacement, Duration.ofSeconds(60));
                return null;
            });
            var logout = executor.submit(() -> {
                start.await();
                store.removeSession(old);
                return null;
            });
            start.countDown();
            save.get(10, TimeUnit.SECONDS);
            logout.get(10, TimeUnit.SECONDS);
        }
        assertThat(store.isCurrentSession(replacement)).isTrue();
        assertThat(store.isCurrentSession(old)).isFalse();
    }

    @Test
    void shouldRejectExpiredSessionTtlWithoutOverwritingExistingValue() {
        reserveSession("PC");
        String key = RedisConstants.buildAuthTokenKey(userId, "PC");
        String original = redis.opsForValue().get(key);
        assertThatThrownBy(() -> store.saveSession(claims("PC"), Duration.ZERO))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
        assertThat(redis.opsForValue().get(key)).isEqualTo(original);
    }

    @Test
    void shouldRemoveAllSupportedClientsAndLeaveUnrelatedKeyIntact() {
        for (String client : new String[]{"PC", "H5", "PLUGIN"}) {
            reserveSession(client);
            store.saveSession(claims(client), Duration.ofSeconds(60));
        }
        String unrelated = "polyhouse:dev:integration:" + UUID.randomUUID();
        reserve(unrelated, "owned-fixture");
        store.removeAllSessions(userId);
        for (String client : new String[]{"PC", "H5", "PLUGIN"}) {
            assertThat(redis.hasKey(RedisConstants.buildAuthTokenKey(userId, client))).isFalse();
        }
        assertThat(redis.opsForValue().get(unrelated)).isEqualTo("owned-fixture");
        store.removeAllSessions(userId);
    }

    private void reserveSession(String client) {
        reserve(RedisConstants.buildAuthTokenKey(userId, client), "reservation-" + UUID.randomUUID());
    }

    private void reserve(String key, String value) {
        assertThat(redis.opsForValue().setIfAbsent(key, value, Duration.ofSeconds(60))).isTrue();
        ownedKeys.add(key);
    }

    private JwtUtils.TokenClaims claims(String client) {
        Instant now = Instant.now();
        return new JwtUtils.TokenClaims(userId, UserRoleEnum.USER, client, UUID.randomUUID().toString(), now,
                now.plusSeconds(60));
    }

    /** Redis 实例测试只加载所需 Bean，不因认证业务而引入 MySQL 依赖。 */
    @TestConfiguration(proxyBeanMethods = false)
    @ImportAutoConfiguration(RedisAutoConfiguration.class)
    @Import({RedisConfig.class, AuthRedisStore.class})
    static class RedisTestConfiguration {
    }
}
