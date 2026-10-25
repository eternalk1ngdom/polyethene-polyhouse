package com.polyethene.polyhouse.common.constant;

import java.time.Duration;

/**
 * 集中维护鉴权 Redis 命名空间和过期策略，避免业务代码手写 Key 或时间参数。
 */
public final class RedisConstants {

    /** JWT 白名单键前缀，完整格式为 polyhouse:auth:token:{userId}:{clientType}。 */
    public static final String AUTH_TOKEN_KEY_PREFIX = "polyhouse:auth:token:";

    /** 登录失败计数键前缀，完整格式为 polyhouse:auth:fail:{username}。 */
    public static final String AUTH_FAILURE_KEY_PREFIX = "polyhouse:auth:fail:";

    /** 未显式指定终端类型时使用的默认 PC 端标识。 */
    public static final String DEFAULT_CLIENT_TYPE = "PC";

    /** 在锁定账号前允许的连续密码错误次数。 */
    public static final int MAX_LOGIN_FAILURES = 5;

    /** 登录失败计数与账号锁定共同使用的十五分钟窗口。 */
    public static final Duration LOGIN_FAILURE_WINDOW = Duration.ofMinutes(15);

    /** JWT 白名单与策划书七天登录有效期保持一致。 */
    public static final Duration AUTH_TOKEN_TTL = Duration.ofDays(7);

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

    /**
     * 构造登录失败计数键，使不存在账号和密码错误共享同一套防枚举策略。
     *
     * @param username 登录账号
     * @return 符合统一命名空间的 Redis Key
     */
    public static String buildAuthFailureKey(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        return AUTH_FAILURE_KEY_PREFIX + username;
    }
}
