package com.polyethene.polyhouse.model.vo;

import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.model.entity.User;

import java.util.Objects;

/**
 * 当前用户的安全资料投影，只允许明确列出的展示字段进入接口响应。
 *
 * @param id 以字符串输出雪花 ID，避免 JavaScript number 丢失 bigint 精度
 * @param username 当前用户自己的登录账号，不包含联系方式
 * @param nickname 供右上角个人卡片展示的昵称
 * @param avatar 头像地址，允许现有数据库中的空值
 * @param role 以 USER/INSIDER/OWNER 语义字符串输出的身份
 */
public record UserProfileVO(String id, String username, String nickname, String avatar, UserRoleEnum role) {

    /**
     * 显式挑选资料字段，排除密码哈希、状态、登录 IP、逻辑删除与任意 JSON 扩展。
     *
     * @param user 已从数据库读取的用户，不应使用尚未完成初始化的临时实体
     * @return 不携带内部管理字段的不可变资料
     */
    public static UserProfileVO from(User user) {
        Objects.requireNonNull(user, "user must not be null");
        Long id = Objects.requireNonNull(user.getId(), "persisted user id must not be null");
        if (id <= 0) {
            throw new IllegalArgumentException("persisted user id must be positive");
        }
        return new UserProfileVO(id.toString(),
                Objects.requireNonNull(user.getUsername(), "persisted username must not be null"),
                user.getNickname(), user.getAvatar(),
                Objects.requireNonNull(user.getRole(), "persisted user role must not be null"));
    }
}
