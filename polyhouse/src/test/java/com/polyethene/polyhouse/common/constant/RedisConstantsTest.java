package com.polyethene.polyhouse.common.constant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RedisConstantsTest {

    @Test
    void shouldBuildNamespacedAuthenticationKeys() {
        assertEquals("polyhouse:auth:token:42:PC", RedisConstants.buildAuthTokenKey(42L, "PC"));
    }

    @Test
    void shouldUsePcAsDefaultClient() {
        assertEquals("PC", RedisConstants.DEFAULT_CLIENT_TYPE);
    }

    @Test
    void shouldRejectInvalidKeyComponents() {
        assertThrows(IllegalArgumentException.class, () -> RedisConstants.buildAuthTokenKey(0L, "PC"));
        assertThrows(IllegalArgumentException.class, () -> RedisConstants.buildAuthTokenKey(42L, " "));
    }
}
