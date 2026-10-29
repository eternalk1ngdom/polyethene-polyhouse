package com.polyethene.polyhouse.model.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 路径中的目标编号经 DTO 校验；不能用请求体覆盖路径目标。 @param id 非零正整数用户编号 */
public record AccountTargetDTO(@NotNull @Positive Long id) { }
