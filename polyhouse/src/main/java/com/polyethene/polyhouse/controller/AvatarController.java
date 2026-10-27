package com.polyethene.polyhouse.controller;

import com.polyethene.polyhouse.common.annotation.RequireRole;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.common.result.Result;
import com.polyethene.polyhouse.model.dto.AvatarUploadDTO;
import com.polyethene.polyhouse.model.dto.SessionTokenDTO;
import com.polyethene.polyhouse.model.vo.AvatarVO;
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

    /** 使用 Bearer 身份修改自己的头像；裁剪在客户端完成，不开放付费处理或任意大小的服务器图像工具。 */
    @RequireRole(UserRoleEnum.USER)
    @PostMapping(value = "/api/auth/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<AvatarVO> upload(@Valid @ModelAttribute AvatarUploadDTO request, @Valid SessionTokenDTO session) {
        return Result.success(service.upload(request, session.token()));
    }
}
