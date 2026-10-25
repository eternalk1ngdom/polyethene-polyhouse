package com.polyethene.polyhouse.common.context;

import com.polyethene.polyhouse.common.constant.UserRoleEnum;

import java.util.Objects;

/**
 * 保存单次请求已认证的用户身份，避免 Controller 和 Service 传递可伪造的用户标识。
 */
public final class UserContext {

    private static final ThreadLocal<UserIdentity> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    /**
     * 原子写入当前请求的用户主键和角色，防止上下文出现只有部分身份信息的状态。
     *
     * @param userId 已认证用户主键
     * @param role 已认证用户角色
     */
    public static void set(long userId, UserRoleEnum role) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        HOLDER.set(new UserIdentity(userId, Objects.requireNonNull(role, "role must not be null")));
    }

    /**
     * 返回当前线程中的用户主键；未认证请求返回 null，由鉴权层决定是否拒绝访问。
     *
     * @return 当前用户主键，未认证时为 null
     */
    public static Long getUserId() {
        UserIdentity identity = HOLDER.get();
        return identity == null ? null : identity.userId;
    }

    /**
     * 返回当前线程中的角色；未认证请求返回 null，业务层不得自行猜测默认角色。
     *
     * @return 当前用户角色，未认证时为 null
     */
    public static UserRoleEnum getRole() {
        UserIdentity identity = HOLDER.get();
        return identity == null ? null : identity.role;
    }

    /**
     * 判断当前线程是否已经写入完整身份，便于拦截器和服务层进行快速校验。
     *
     * @return 存在完整身份时返回 true
     */
    public static boolean isAuthenticated() {
        return HOLDER.get() != null;
    }

    /**
     * 清理线程变量，必须在请求完成后调用以防线程池复用造成身份串用和内存泄漏。
     */
    public static void clear() {
        HOLDER.remove();
    }

    private static final class UserIdentity {

        private final long userId;
        private final UserRoleEnum role;

        private UserIdentity(long userId, UserRoleEnum role) {
            this.userId = userId;
            this.role = role;
        }
    }
}
