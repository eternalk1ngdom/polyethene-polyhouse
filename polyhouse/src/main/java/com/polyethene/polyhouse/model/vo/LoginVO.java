package com.polyethene.polyhouse.model.vo;

import java.time.Instant;

/**
 * 登录成功的安全响应边界；令牌必须交付给调用者，但不能进入默认调试文本。
 *
 * @param accessToken 已加入白名单的访问令牌，不包含密码或密码哈希
 * @param tokenType HTTP Authorization 使用的 Bearer 类型
 * @param expiresAt 令牌实际过期时刻，客户端据此处理会话有效期
 * @param user 明确投影后的用户资料，不返回持久化 Entity
 */
public record LoginVO(String accessToken, String tokenType, Instant expiresAt, UserProfileVO user) {

    /** 避免 record 自动生成的文本表示将可用令牌写入日志。 */
    @Override
    public String toString() {
        return "LoginVO[credentials redacted]";
    }
}
