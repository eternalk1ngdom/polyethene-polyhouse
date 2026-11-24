package com.polyethene.polyhouse.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** @param coverKey 本人申请佩戴的款式，权限由服务端重新核对 */
public record CoverEquipDTO(@NotBlank @Size(max=24) String coverKey) {}
