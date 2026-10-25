package com.polyethene.polyhouse.common.constant;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RedisConstantsTest {

    @Test
    void shouldBuildNamespacedAuthenticationKeys() {
        assertEquals("polyhouse:auth:token:42:PC", RedisConstants.buildAuthTokenKey(42L, "PC"));
        assertEquals("polyhouse:auth:fail:alice", RedisConstants.buildAuthFailureKey("alice"));
    }

    @Test
    void shouldKeepDocumentedAuthenticationLimits() {
        assertEquals(5, RedisConstants.MAX_LOGIN_FAILURES);
        assertEquals(Duration.ofMinutes(15), RedisConstants.LOGIN_FAILURE_WINDOW);
        assertEquals(Duration.ofDays(7), RedisConstants.AUTH_TOKEN_TTL);
    }

    @Test
    void shouldRejectInvalidKeyComponents() {
        assertThrows(IllegalArgumentException.class, () -> RedisConstants.buildAuthTokenKey(0L, "PC"));
        assertThrows(IllegalArgumentException.class, () -> RedisConstants.buildAuthTokenKey(42L, " "));
        assertThrows(IllegalArgumentException.class, () -> RedisConstants.buildAuthFailureKey(""));
    }
}
