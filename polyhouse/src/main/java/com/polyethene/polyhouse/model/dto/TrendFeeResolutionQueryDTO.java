package com.polyethene.polyhouse.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 提交响应不确定时只确认同一处理事实，不换编号反复操作。
 * @param resolutionId 已提交的保守关闭意图UUID
 */
public record TrendFeeResolutionQueryDTO(
        @NotBlank @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String resolutionId) { }
