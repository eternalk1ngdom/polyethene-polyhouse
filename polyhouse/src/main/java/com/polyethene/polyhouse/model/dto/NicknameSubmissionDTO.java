package com.polyethene.polyhouse.model.dto;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.*;
/** 昵称只是申请，不直接更新公开用户表。
 * @param requestId 同一请求的防重编号
 * @param nickname 1～32 字符纯文本，不包含换行/隐藏控制字符
 */
public record NicknameSubmissionDTO(@NotBlank @Pattern(regexp="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String requestId,
        @NotBlank @Size(max=32) String nickname) {
    /** 不静默裁剪，明确拒绝可影响审计与展示的控制、格式和换行字符。 */
    @AssertTrue @JsonIgnore public boolean isSafeText() {
        return nickname == null || nickname.codePoints().noneMatch(c -> Character.isISOControl(c)
            || Character.getType(c)==Character.FORMAT || Character.getType(c)==Character.LINE_SEPARATOR
            || Character.getType(c)==Character.PARAGRAPH_SEPARATOR);
    }
}
