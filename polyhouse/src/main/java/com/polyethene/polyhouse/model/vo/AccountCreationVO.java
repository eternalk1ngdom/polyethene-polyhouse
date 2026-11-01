package com.polyethene.polyhouse.model.vo;

import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.model.entity.AccountCreation;

/**
 * 一次凭据交付与后续事实核对共享显式投影，不复用含内部字段的实体。
 * @param id 字符串编号，避免浏览器丢失雪花 ID 精度
 * @param username 系统分配的不可变登录账号
 * @param nickname 系统生成的初始昵称
 * @param role 创建时授予的角色，并非后续权限快照
 * @param firstDelivery 仅本次事务首次成功创建才为 true
 * @param initialPassword 仅首次响应可有值，重放/查询固定为 null
 */
public record AccountCreationVO(String id, String username, String nickname, UserRoleEnum role,
                                boolean firstDelivery, String initialPassword) {
    /** 历史响应不重新生成或查回密码，避免“幂等”变成凭据读取接口。 */
    public static AccountCreationVO confirmed(AccountCreation record) {
        return new AccountCreationVO(Long.toString(record.targetId()), record.username(), record.nickname(), record.role(), false, null);
    }

    /** 即使调用方诊断打印返回对象，也不会输出初始凭据。 */
    @Override public String toString() { return "AccountCreationVO[credentials redacted]"; }
}
