package com.polyethene.polyhouse.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 会话命令的专用请求体，避免把凭据放在 URL 或将用户实体绑定到接口。
 *
 * @param token 不带 Bearer 前缀的 JWT，仅用于当前请求的服务端验签与撤销
 */
public record SessionTokenDTO(
        @NotBlank(message = "登录令牌不能为空")
        @Size(max = 4096, message = "登录令牌过长")
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        String token) {

    /** 隐藏 record 默认文本中的凭据，避免调试输出意外泄露 Token。 */
    @Override
    public String toString() {
        return "SessionTokenDTO[credentials redacted]";
    }
}
