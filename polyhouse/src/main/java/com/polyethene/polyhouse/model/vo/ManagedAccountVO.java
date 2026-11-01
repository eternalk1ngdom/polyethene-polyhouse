package com.polyethene.polyhouse.model.vo;

import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.model.entity.ManagedAccount;
import java.time.LocalDateTime;

/**
 * 管理列表只提供必要字段，屋主自身只读；版本/编号用字符串保留 long 精度。
 * @param id 用户编号
 * @param username 不可修改的登录名
 * @param nickname 当前展示昵称，按纯文本显示
 * @param role 最新角色，用于明确操作对象
 * @param status 0 停用、1 启用
 * @param version 本管理模块的并发版本，不暴露 JWT 凭据版本
 * @param passwordChangeRequired 是否仍使用临时凭据
 * @param readOnly OWNER 自身禁止管理操作
 * @param createdAt 账号创建时间
 */
public record ManagedAccountVO(String id, String username, String nickname, UserRoleEnum role,
                               int status, String version, boolean passwordChangeRequired,
                               boolean readOnly, LocalDateTime createdAt) {
    /** 在唯一投影处固定字段，不能意外序列化整个 User。 */
    public static ManagedAccountVO from(ManagedAccount account) {
        return new ManagedAccountVO(Long.toString(account.id()), account.username(), account.nickname(), account.role(),
                account.status(), Long.toString(account.managementVersion()), account.passwordChangeRequired(),
                account.role() == UserRoleEnum.OWNER, account.createdAt());
    }
}
