package com.polyethene.polyhouse.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 有界按编号读取，不能枚举账号或获取原始材料。
 * @param jobId 已受理的任务 UUID
 */
public record TrendJobQueryDTO(@NotBlank @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String jobId) { }
