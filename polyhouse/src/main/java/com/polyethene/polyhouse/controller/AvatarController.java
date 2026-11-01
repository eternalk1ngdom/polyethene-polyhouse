package com.polyethene.polyhouse.controller;

import com.polyethene.polyhouse.common.annotation.RequireRole;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.common.result.Result;
import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.model.dto.AvatarUploadDTO;
import com.polyethene.polyhouse.model.dto.SessionTokenDTO;
import com.polyethene.polyhouse.model.vo.ProfileSubmissionVO;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import com.polyethene.polyhouse.service.AvatarService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 头像属于用户已明确要求向普通账号开放的基本资料能力，处理限制为单张 256 像素小图。 */
@RestController
public class AvatarController {
    private final AvatarService service;

    /** 控制器不处理文件路径、图片解码或数据库事务。 */
    public AvatarController(AvatarService service) {
        this.service = service;
    }

    /** 使用 Bearer 身份提交自己的头像审核申请；裁剪在客户端完成，不开放付费处理或任意大小的服务器图像工具。 */
    @RequireRole(UserRoleEnum.USER)
    @PostMapping(value = "/api/auth/avatar/submissions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Result<ProfileSubmissionVO>> upload(@Valid @ModelAttribute AvatarUploadDTO request, @Valid SessionTokenDTO session) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Result.success(service.upload(request, session.token())));
    }
    /** 关闭旧的立即发布地址；新旧前后端混用也不能因响应解析发生在写入之后而绕过审核。 */
    @RequireRole(UserRoleEnum.USER)
    @PostMapping(value="/api/auth/avatar", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Result<ProfileSubmissionVO>> legacy(@Valid SessionTokenDTO session) {
        throw new BusinessException(ErrorCode.CONFLICT,"头像上传已改为审核申请，请刷新新版页面");
    }
}
