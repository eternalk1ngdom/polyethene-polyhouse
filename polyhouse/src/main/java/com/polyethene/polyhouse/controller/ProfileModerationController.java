package com.polyethene.polyhouse.controller;
import com.polyethene.polyhouse.common.annotation.RequireRole;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.common.result.Result;
import com.polyethene.polyhouse.model.dto.*;
import com.polyethene.polyhouse.model.vo.*;
import com.polyethene.polyhouse.service.ProfileModerationService;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** 资料审核的独立 HTTP 契约；图片二进制是明确的媒体响应例外，错误仍走统一 Result。 */
@RestController @RequireRole(UserRoleEnum.USER)
public class ProfileModerationController {
    private final ProfileModerationService service;
    /** Controller 不接收实体、磁盘路径或客户端指定申请人。 */
    public ProfileModerationController(ProfileModerationService service) { this.service=service; }
    /** 本人昵称仅提交，公开值在 OWNER 批准后改变。 */
    @PostMapping("/api/auth/nickname/submissions")
    public ResponseEntity<Result<ProfileSubmissionVO>> nickname(@Valid @RequestBody NicknameSubmissionDTO dto,@Valid SessionTokenDTO session) {
        return response(service.submitNickname(dto,session.token()));
    }
    /** 本人申请/额度，不能附带用户编号读取他人资料。 */
    @GetMapping("/api/auth/profile-submissions")
    public ResponseEntity<Result<ProfileStatusVO>> mine(@Valid SessionTokenDTO session) { return response(service.mine(session.token())); }
    /** OWNER 待审队列，ADMIN/INSIDER 都没有审核权。 */
    @RequireRole(UserRoleEnum.OWNER) @GetMapping("/api/manage/profile-reviews")
    public ResponseEntity<Result<ProfileReviewPageVO>> queue(@Valid @ModelAttribute ProfileReviewQueryDTO dto,@Valid SessionTokenDTO session) {
        return response(service.queue(dto,session.token()));
    }
    /** 私有图片必须带 Bearer 且通过所有权检查，禁止进入共享 HTTP 缓存。 */
    @GetMapping("/api/profile-submissions/{id}/image")
    public ResponseEntity<byte[]> image(@Valid @PathVariable("id") ProfileResourceDTO id,@Valid SessionTokenDTO session) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff")
            .contentType(MediaType.IMAGE_JPEG).body(service.image(id,session.token()));
    }
    /** 只审核 URI 中的申请及 DTO 指定版本，不能覆盖申请内容。 */
    @RequireRole(UserRoleEnum.OWNER) @PostMapping("/api/manage/profile-reviews/{id}/decision")
    public ResponseEntity<Result<ProfileSubmissionVO>> decide(@Valid @PathVariable("id") ProfileResourceDTO id,
        @Valid @RequestBody ProfileDecisionDTO dto,@Valid SessionTokenDTO session) { return response(service.decide(id,dto,session.token())); }
    /** 历史内容保留/下架前先清点，返回必要字段和未核对标记。 */
    @RequireRole(UserRoleEnum.OWNER) @GetMapping("/api/manage/profile-published")
    public ResponseEntity<Result<PublishedProfilePageVO>> published(@Valid @ModelAttribute ProfilePublishedQueryDTO dto,@Valid SessionTokenDTO session) {
        return response(service.published(dto,session.token()));
    }
    /** 下架按字段定向写入，昵称与头像不能互相覆盖。 */
    @RequireRole(UserRoleEnum.OWNER) @PostMapping("/api/manage/accounts/{id}/profile/takedown")
    public ResponseEntity<Result<PublishedProfileVO>> changePublic(@Valid @PathVariable("id") ProfileResourceDTO id,
        @Valid @RequestBody ProfilePublicChangeDTO dto,@Valid SessionTokenDTO session) { return response(service.changePublic(id,dto,session.token())); }
    private <T>ResponseEntity<Result<T>> response(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Result.success(value)); }
}
