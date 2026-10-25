package com.polyethene.polyhouse.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 仅用于 MVC 测试，验证 JSON、表单和方法校验都通过 DTO 边界执行。
 *
 * @param label 测试输入标签，必须非空，验证消息也不能泄露其内容
 * @param count 必填正整数，用于验证数值转换和嵌套约束
 */
public record ValidationProbeDTO(
        @NotBlank String label,
        @NotNull @Positive Integer count) {
}
