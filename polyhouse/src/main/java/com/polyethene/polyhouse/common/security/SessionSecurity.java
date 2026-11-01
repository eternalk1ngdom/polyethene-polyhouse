package com.polyethene.polyhouse.common.security;

import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.model.entity.User;

/** 对调用方提供的最新用户快照执行纯安全判断；不访问存储，写业务仍必须持有用户行锁。 */
public final class SessionSecurity {
    private SessionSecurity() { }

    /** 版本检查先于奖励或写操作；缺字段代表迁移/数据异常，不能伪造默认值放行。 */
    public static void requireCurrent(User user, long expectedVersion) {
        if (user == null || !Integer.valueOf(1).equals(user.getStatus())
                || !Integer.valueOf(0).equals(user.getDeleted()) || user.getRole() == null
                || user.getCredentialVersion() == null || user.getCredentialVersion() < 0
                || user.getPasswordChangeRequired() == null || expectedVersion < 0
                || user.getCredentialVersion() != expectedVersion) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "登录状态已失效，请重新登录");
        }
    }

    /** 临时凭据不能进入受保护业务；账号状态和版本必须先经过 requireCurrent 核对。 */
    public static void requireFullAccess(User user) {
        if (Boolean.TRUE.equals(user.getPasswordChangeRequired())) {
            throw new BusinessException(ErrorCode.PASSWORD_CHANGE_REQUIRED);
        }
    }
}
