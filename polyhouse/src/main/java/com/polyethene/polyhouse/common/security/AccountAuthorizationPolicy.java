package com.polyethene.polyhouse.common.security;

import com.polyethene.polyhouse.common.constant.UserRoleEnum;

/** 管理权限按操作者、动作和目标角色决定；调用方在写事务中获取最新角色，不能传客户端缓存。 */
public final class AccountAuthorizationPolicy {
    private AccountAuthorizationPolicy() { }

    /** 管理员只能进入普通用户管理，特邀熟人的高开销资格不构成管理资格。 */
    public static boolean canManageAccounts(UserRoleEnum actor) {
        return actor == UserRoleEnum.ADMIN || actor == UserRoleEnum.OWNER;
    }

    /** 屋主只能创建允许发放的两种角色，不能借网页再生成 OWNER。 */
    public static boolean canCreate(UserRoleEnum actor, UserRoleEnum target) {
        return actor == UserRoleEnum.OWNER && (target == UserRoleEnum.USER || target == UserRoleEnum.ADMIN);
    }

    /** 管理员只封禁 USER；屋主可封禁其他已知非屋主角色，自身保护由唯一 OWNER 自然满足。 */
    public static boolean canBan(UserRoleEnum actor, UserRoleEnum target) {
        return target != null && target != UserRoleEnum.OWNER
                && (actor == UserRoleEnum.OWNER || actor == UserRoleEnum.ADMIN && target == UserRoleEnum.USER);
    }

    /** 解封和重置仅由屋主对其他账号执行，防止管理员撤销屋主处置或接管他人身份。 */
    public static boolean canRecoverAccount(UserRoleEnum actor, UserRoleEnum target) {
        return actor == UserRoleEnum.OWNER && target != null && target != UserRoleEnum.OWNER;
    }

    /** 资料审核只交给屋主；提交者与目标账号状态仍需后续审核事务核对。 */
    public static boolean canReview(UserRoleEnum actor) { return actor == UserRoleEnum.OWNER; }
}
