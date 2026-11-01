package com.polyethene.polyhouse.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;

/**
 * 屋主只选择角色和创建意图；账号、昵称、权限状态及凭据均由服务端生成。
 * @param requestId 同一次创建/重试保持不变，防止网络超时后生成两个账号
 * @param role 仅 USER/ADMIN 由锁内授权策略放行
 * @param ownerPassword 创建管理员时再次确认当前屋主凭据，不持久保存
 */
public record AccountCreateDTO(
        @NotBlank @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String requestId,
        @NotNull UserRoleEnum role,
        @Size(max = 72) @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String ownerPassword) {
    /** 普通开户允许不传密码；管理员开户不接受空白/过短或超出 BCrypt 字节上限的再验证。 */
    @AssertTrue @JsonIgnore
    public boolean isOwnerConfirmationValid() {
        return (ownerPassword == null || ownerPassword.getBytes(StandardCharsets.UTF_8).length <= 72)
                && (role != UserRoleEnum.ADMIN || ownerPassword != null && !ownerPassword.isBlank() && ownerPassword.length() >= 8);
    }

    /** 防止调试、异常或后续审计误用 record 默认输出泄露屋主密码。 */
    @Override public String toString() { return "AccountCreateDTO[credentials redacted]"; }
}
