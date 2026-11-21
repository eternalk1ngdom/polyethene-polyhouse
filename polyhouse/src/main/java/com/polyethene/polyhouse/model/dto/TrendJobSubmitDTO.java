package com.polyethene.polyhouse.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 请求只表达一次当前日榜更新意图，不接受来源URL、历史日期或绕过预算的参数。
 * @param requestId 客户端为本次更新生成的 UUID，重试必须沿用
 */
public record TrendJobSubmitDTO(@NotBlank @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String requestId) { }
