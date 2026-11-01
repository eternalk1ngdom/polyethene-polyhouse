package com.polyethene.polyhouse.service;
import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.model.dto.AvatarUploadDTO;
import com.polyethene.polyhouse.model.vo.ProfileSubmissionVO;
import com.polyethene.polyhouse.utils.AvatarImageUtils;
import org.springframework.stereotype.Service;
import java.io.IOException;
/** 复用裁剪图校验入口，只转交审核申请，不能绕过审核直接发布。 */
@Service public class AvatarService {
    private final AuthService auth;
    private final ProfileModerationService moderation;
    /** 认证先于图像解码，申请状态/配额/文件补偿交给独立审核服务。 */
    public AvatarService(AuthService auth,ProfileModerationService moderation) { this.auth=auth;this.moderation=moderation; }
    /** 必须携带防重编号；旧页面得到明确拒绝，不能误认为上传已公开。 */
    public ProfileSubmissionVO upload(AvatarUploadDTO request,String token) {
        if (auth.getCurrentUser(token).passwordChangeRequired()) throw new BusinessException(ErrorCode.PASSWORD_CHANGE_REQUIRED);
        if (request==null || request.file()==null || request.file().isEmpty()) throw new BusinessException(ErrorCode.INVALID_PARAMETER);
        if (request.file().getSize()>256*1024) throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE);
        try { return moderation.submitAvatar(request,AvatarImageUtils.reencode(request.file().getBytes()),token); }
        catch(IOException e) { throw new BusinessException(ErrorCode.INVALID_PARAMETER); }
    }
}
