package com.polyethene.polyhouse.common.constant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 校验鉴权会话键的命名空间格式与非法入参防线。 */
class RedisConstantsTest {

    @Test
    void shouldBuildNamespacedAuthenticationKeys() {
        assertEquals("polyhouse:auth:token:42:PC",
                RedisConstants.buildAuthTokenKey(42L, RedisConstants.DEFAULT_CLIENT_TYPE));
    }

    @ParameterizedTest(name = "非法键入参拒绝：{0}")
    @MethodSource("invalidKeyComponents")
    void shouldRejectInvalidKeyComponents(String label, long userId, String clientType) {
        assertThrows(IllegalArgumentException.class, () -> RedisConstants.buildAuthTokenKey(userId, clientType));
    }

    private static Stream<Arguments> invalidKeyComponents() {
        return Stream.of(
                Arguments.of("userId 为 0", 0L, "PC"),
                Arguments.of("userId 为负数", -1L, "PC"),
                Arguments.of("clientType 为 null", 42L, null),
                Arguments.of("clientType 为空串", 42L, ""),
                Arguments.of("clientType 为空白串", 42L, " ")
        );
    }
}
