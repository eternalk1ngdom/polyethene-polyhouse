package com.polyethene.polyhouse.model.dto;
import com.polyethene.polyhouse.common.constant.*;
import jakarta.validation.constraints.*;
/** 历史保留或下架必须精确匹配当前展示，避免旧清单处置新内容。
 * @param kind 仅 NICKNAME/AVATAR
 * @param expectedVersion 管理列表中的字段版本
 * @param expectedValue 当前显示内容；默认头像允许 null，不是客户端要更新的新值
 * @param action KEEP 确认历史、TAKEDOWN 下架
 * @param reason 必填必要原因
 */
public record ProfilePublicChangeDTO(@NotNull ProfileField kind, @NotNull @PositiveOrZero Long expectedVersion,
        @Size(max=255) String expectedValue, @NotNull ProfilePublicAction action,
        @NotBlank @Size(max=200) String reason) { }
