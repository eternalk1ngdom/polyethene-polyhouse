package com.polyethene.polyhouse.common.constant;

/**
 * 集中维护鉴权 Redis 命名空间和默认终端，避免业务代码手写会话键。
 */
public final class RedisConstants {

    /** JWT 白名单键前缀，完整格式为 polyhouse:auth:token:{userId}:{clientType}。 */
    public static final String AUTH_TOKEN_KEY_PREFIX = "polyhouse:auth:token:";

    /** 未显式指定终端类型时使用的默认 PC 端标识。 */
    public static final String DEFAULT_CLIENT_TYPE = "PC";

    private RedisConstants() {
    }

    /**
     * 构造用户在指定客户端上的 JWT 白名单键，确保多端登录彼此隔离。
     *
     * @param userId 用户主键
     * @param clientType 客户端类型，例如 PC、H5 或 PLUGIN
     * @return 符合统一命名空间的 Redis Key
     */
    public static String buildAuthTokenKey(long userId, String clientType) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (clientType == null || clientType.isBlank()) {
            throw new IllegalArgumentException("clientType must not be blank");
        }
        return AUTH_TOKEN_KEY_PREFIX + userId + ":" + clientType;
    }

}
