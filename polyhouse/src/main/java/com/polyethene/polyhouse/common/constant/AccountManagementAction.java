package com.polyethene.polyhouse.common.constant;

/** 受控管理动作白名单；不接受客户端提供 SQL 字段或任意角色编辑动作。 */
public enum AccountManagementAction {
    /** 停用目标并持久撤销其历史凭据。 */
    BAN,
    /** 仅恢复可登录状态，不恢复任何历史会话。 */
    UNBAN,
    /** 发放新的临时密码，保持目标原有启停状态。 */
    PASSWORD_RESET
}
