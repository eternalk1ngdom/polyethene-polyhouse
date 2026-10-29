package com.polyethene.polyhouse.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 响应丢失后只核对同一屋主创建事实，不查询原始密码。
 * @param requestId 当前创建意图的 UUID，由当前会话的操作者限定查询范围
 */
public record AccountCreationQueryDTO(
        @NotBlank @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String requestId) { }
