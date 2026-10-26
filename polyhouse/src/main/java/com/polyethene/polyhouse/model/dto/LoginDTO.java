package com.polyethene.polyhouse.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.nio.charset.StandardCharsets;

/**
 * 将登录凭据与持久化实体隔离，账号和密码规则与本项目注册边界保持一致。
 *
 * @param username 4 至 32 位字母、数字或下划线账号，不在边界偷偷裁剪或改写
 * @param password 原始密码，不裁剪空格、不做 Unicode 归一化，且限制 BCrypt 的 UTF-8 输入长度
 * @param clientType 可选终端标识 PC/H5/PLUGIN，未提供时由业务层使用默认 PC
 */
public record LoginDTO(
        @NotBlank(message = "账号不能为空")
        @Size(min = 4, max = 32, message = "账号长度须为 4 至 32 个字符")
        @Pattern(regexp = "[A-Za-z0-9_]+", message = "账号仅允许字母、数字和下划线")
        String username,
        @NotBlank(message = "密码不能为空")
        @Size(min = 8, max = 72, message = "密码长度须为 8 至 72 个字符")
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        String password,
        @Pattern(regexp = "PC|H5|PLUGIN", message = "终端类型不支持")
        String clientType) {

    /** 保留仅提供账号密码的调用方式，省略终端类型时使用默认 PC。 */
    public LoginDTO(String username, String password) {
        this(username, password, null);
    }

    /** 按字节约束与注册端一致，避免超过 BCrypt 输入上限的密码被静默截断。 */
    @AssertTrue(message = "密码的 UTF-8 长度不能超过 72 字节")
    @JsonIgnore
    public boolean isPasswordWithinBcryptLimit() {
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    /** 默认 record 文本会包含密码，因此只输出不含输入值的类型提示。 */
    @Override
    public String toString() {
        return "LoginDTO[credentials redacted]";
    }
}
