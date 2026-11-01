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
 * @param role 以 USER/ADMIN/INSIDER/OWNER 语义字符串输出的身份
 * @param experience 累计经验以十进制字符串输出，避免 JavaScript 大整数精度损失
 * @param level 成长等级从 1 到 6，不授予任何角色权限
 * @param coins 整数硬币余额以十进制字符串输出，每日奖励已启用，消费用途待定
 * @param passwordChangeRequired 临时凭据仅可读取必要资料、改密和退出，后端独立强制执行
 */
public record UserProfileVO(String id, String username, String nickname, String avatar, UserRoleEnum role,
                            String experience, int level, String coins, boolean passwordChangeRequired) {

    /** 保留旧内部资料投影构造方式；真实账号始终通过 from 显式读取数据库的改密状态。 */
    public UserProfileVO(String id, String username, String nickname, String avatar, UserRoleEnum role,
                         String experience, int level, String coins) {
        this(id, username, nickname, avatar, role, experience, level, coins, false);
    }

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
        // 数据库迁移必须先于新应用上线；拒绝缺失或非法成长数据，不伪造余额掩盖数据问题。
        Long experience = Objects.requireNonNull(user.getExperience(), "persisted experience must not be null");
        Integer level = Objects.requireNonNull(user.getLevel(), "persisted level must not be null");
        Long coins = Objects.requireNonNull(user.getCoins(), "persisted coins must not be null");
        if (experience < 0 || level < 1 || coins < 0) {
            throw new IllegalArgumentException("persisted growth values are outside their valid range");
        }
        return new UserProfileVO(id.toString(),
                Objects.requireNonNull(user.getUsername(), "persisted username must not be null"),
                user.getNickname(), user.getAvatar(),
                Objects.requireNonNull(user.getRole(), "persisted user role must not be null"),
                experience.toString(), level, coins.toString(),
                Objects.requireNonNull(user.getPasswordChangeRequired(), "persisted password state must not be null"));
    }
}
