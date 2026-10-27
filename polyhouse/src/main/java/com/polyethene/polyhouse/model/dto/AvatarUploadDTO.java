package com.polyethene.polyhouse.model.dto;

import jakarta.validation.constraints.NotNull;
import org.springframework.web.multipart.MultipartFile;

/** 只接受裁剪后的图片，不接收用户编号、文件路径或角色，避免修改他人资料。 */
public record AvatarUploadDTO(
        /** 文件还需在业务层核验真实格式、大小和像素，不能相信扩展名。 */
        @NotNull MultipartFile file) {
}
