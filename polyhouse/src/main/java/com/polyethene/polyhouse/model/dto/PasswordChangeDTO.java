package com.polyethene.polyhouse.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.nio.charset.StandardCharsets;

/**
 * 本人改密不接收用户编号、角色或凭据版本，身份只来自 Bearer 会话。
 * @param currentPassword 当前原始密码，用于重新确认操作者知道凭据，不自动裁剪空格
 * @param newPassword 新原始密码，字符和 UTF-8 字节双重限制以适配 BCrypt
 */
public record PasswordChangeDTO(
        @NotBlank @Size(min = 8, max = 72) @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        String currentPassword,
        @NotBlank @Size(min = 8, max = 72) @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        String newPassword) {

    /** 统一拒绝多字节输入超过 BCrypt 上限，避免不同密码尾部被截断为同一值。 */
    @AssertTrue
    @JsonIgnore
    public boolean isWithinBcryptLimit() {
        return (currentPassword == null || currentPassword.getBytes(StandardCharsets.UTF_8).length <= 72)
                && (newPassword == null || newPassword.getBytes(StandardCharsets.UTF_8).length <= 72);
    }

    /** 覆盖 record 诊断输出，异常和调试均不能携带原始凭据。 */
    @Override
    public String toString() { return "PasswordChangeDTO[credentials redacted]"; }
}
