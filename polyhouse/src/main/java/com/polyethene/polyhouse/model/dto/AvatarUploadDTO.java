package com.polyethene.polyhouse.model.dto;
import jakarta.validation.constraints.*;
import org.springframework.web.multipart.MultipartFile;
/** 裁剪图和请求编号组成一次申请；旧客户端缺少编号将被拒绝，不伪报已发布。
 * @param file 业务层核验真实图片与尺寸，不信任扩展名
 * @param requestId 同一提交/重试沿用的小写 UUID，不接受用户编号或文件路径
 */
public record AvatarUploadDTO(@NotNull MultipartFile file,
        @NotBlank @Pattern(regexp="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String requestId) { }
