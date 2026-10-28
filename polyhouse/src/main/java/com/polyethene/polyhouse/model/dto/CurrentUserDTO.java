package com.polyethene.polyhouse.model.dto;

import com.polyethene.polyhouse.model.vo.UserProfileVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * 传递本次 HTTP 请求已通过认证及角色检查的脱敏资料，不从请求体或查询参数绑定身份。
 * 只用于读取已核验的本人资料；写业务仍须在事务内自行复核账号和会话。
 *
 * @param profile 拦截器通过 AuthService 核验所得的不可变资料，不包含 Token 或持久化实体
 */
public record CurrentUserDTO(@NotNull @Valid UserProfileVO profile) {
}
