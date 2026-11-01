package com.polyethene.polyhouse.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;

/**
 * 显式携带列表快照防止误处置新状态；所有角色和状态仍须锁内核对。
 * @param requestId 本次操作/重试的同一小写 UUID
 * @param expectedVersion 列表中的管理版本，不是 JWT 凭据版本
 * @param expectedRole 页面确认时的目标角色，角色变化必须重新确认
 * @param expectedStatus 页面确认时的启停状态
 * @param reason 最多二百字的必要原因，不应包含密码或私人信息
 * @param ownerPassword 高风险动作再次验证屋主，绝不持久保存
 */
public record AccountOperationDTO(
        @NotBlank @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String requestId,
        @NotNull @PositiveOrZero Long expectedVersion,
        @NotNull UserRoleEnum expectedRole, @NotNull @Min(0) @Max(1) Integer expectedStatus,
        @NotBlank @Size(max = 200) String reason,
        @Size(max = 72) @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String ownerPassword) {
    /** 禁止换行/隐藏控制字符污染审计，不裁剪密码或把任意长密码交给 BCrypt。 */
    @AssertTrue @JsonIgnore
    public boolean isSafeInput() {
        return (reason == null || reason.codePoints().noneMatch(c -> Character.isISOControl(c)
                || Character.getType(c) == Character.FORMAT))
                && (ownerPassword == null || ownerPassword.getBytes(StandardCharsets.UTF_8).length <= 72);
    }

    /** record 默认输出包含密码和原因，此处明确屏蔽整个输入。 */
    @Override public String toString() { return "AccountOperationDTO[input redacted]"; }
}
