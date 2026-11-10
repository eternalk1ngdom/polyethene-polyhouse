package com.polyethene.polyhouse.model.dto;

import com.polyethene.polyhouse.utils.AvatarImageUtils;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 浏览器只交付裁剪后的图片和防重编号，提交人由会话确定。
 * @param avatarBase64 标准 Base64，不含 data URL 前缀
 * @param requestId 同一提交重试使用相同的 UUID，避免多次计额
 */
public record AvatarSubmitDTO(
    @NotBlank @Size(max = AvatarImageUtils.MAX_BASE64_LENGTH) String avatarBase64,
    @NotBlank @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String requestId
) { }
