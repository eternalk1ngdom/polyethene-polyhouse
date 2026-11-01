package com.polyethene.polyhouse.model.dto;
import jakarta.validation.constraints.*;
/** 路径编号不能被同名查询参数替换。 @param id 精确申请或账号编号 */
public record ProfileResourceDTO(@NotNull @Positive Long id) { }
