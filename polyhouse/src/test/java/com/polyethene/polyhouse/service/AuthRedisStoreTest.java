package com.polyethene.polyhouse.service;

import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.utils.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/** 会话存储故障不能被解释为有效身份或保存成功。 */
@ExtendWith(MockitoExtension.class)
class AuthRedisStoreTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> values;

    private AuthRedisStore store;

    @BeforeEach
    void prepareStore() {
        store = new AuthRedisStore(redis);
    }

    @Test
    void shouldNotTreatRedisConnectionFailureAsValidSession() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("sensitive-connection-fixture"));
        assertUnavailable(() -> store.isCurrentSession(claims()));
    }

    @Test
    void shouldNotTreatFailedWriteAsSavedSession() {
        when(redis.opsForValue()).thenReturn(values);
        doThrow(new RedisConnectionFailureException("sensitive-connection-fixture"))
                .when(values).set(anyString(), anyString(), any(Duration.class));
        assertUnavailable(() -> store.saveSession(claims(), Duration.ofSeconds(60)));
    }

    @Test
    void shouldRejectMissingScriptResultRatherThanAssumeSuccess() {
        assertUnavailable(() -> store.removeSession(claims()));
    }

    @Test
    void shouldDeleteOnlyExactUserClientKeysAndAcceptAlreadyMissingSessions() {
        List<String> keys = List.of("polyhouse:auth:token:42:PC", "polyhouse:auth:token:42:H5",
                "polyhouse:auth:token:42:PLUGIN");
        when(redis.delete(keys)).thenReturn(0L);
        store.removeAllSessions(42L);
        verify(redis).delete(keys);
    }

    @Test
    void shouldReportFailureWhenAllSessionCleanupCannotBeConfirmed() {
        List<String> keys = List.of("polyhouse:auth:token:42:PC", "polyhouse:auth:token:42:H5",
                "polyhouse:auth:token:42:PLUGIN");
        when(redis.delete(keys)).thenReturn(null);
        assertUnavailable(() -> store.removeAllSessions(42L));
        when(redis.delete(keys)).thenThrow(new RedisConnectionFailureException("sensitive-connection-fixture"));
        assertUnavailable(() -> store.removeAllSessions(42L));
    }

    private static JwtUtils.TokenClaims claims() {
        Instant now = Instant.parse("2026-10-26T08:00:00Z");
        return new JwtUtils.TokenClaims(42L, UserRoleEnum.USER, "PC", UUID.randomUUID().toString(),
                now, now.plusSeconds(60));
    }

    private static void assertUnavailable(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, exception -> {
            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
            assertThat(exception.getMessage()).isEqualTo("认证服务暂时不可用，请稍后重试");
            assertThat(exception.getCause()).isNull();
        });
    }
}
