package com.polyethene.polyhouse.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.nio.charset.StandardCharsets;

/**
 * 仅接收注册必需信息，角色、账号状态和审计字段全部由服务端管理。
 *
 * @param username 4 至 32 位字母、数字或下划线账号，避免空格及不可见字符带来的身份歧义
 * @param password 至少 8 个字符且 UTF-8 不超过 72 字节，兼容策划书指定的 BCrypt
 * @param nickname 1 至 32 个字符的非空白展示昵称，与登录账号独立
 */
public record RegisterDTO(
        @NotBlank(message = "账号不能为空")
        @Size(min = 4, max = 32, message = "账号长度须为 4 至 32 个字符")
        @Pattern(regexp = "[A-Za-z0-9_]+", message = "账号仅允许字母、数字和下划线")
        String username,
        @NotBlank(message = "密码不能为空")
        @Size(min = 8, max = 72, message = "密码长度须为 8 至 72 个字符")
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        String password,
        @NotBlank(message = "昵称不能为空")
        @Size(max = 32, message = "昵称不能超过 32 个字符")
        String nickname) {

    /** BCrypt 的限制按 UTF-8 字节计算，单独使用字符长度约束会遗漏中文等多字节输入。 */
    @AssertTrue(message = "密码的 UTF-8 长度不能超过 72 字节")
    @JsonIgnore
    public boolean isPasswordWithinBcryptLimit() {
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    /** 覆盖 record 默认输出，防止调试或异常日志意外携带明文凭据。 */
    @Override
    public String toString() {
        return "RegisterDTO[credentials redacted]";
    }
}
