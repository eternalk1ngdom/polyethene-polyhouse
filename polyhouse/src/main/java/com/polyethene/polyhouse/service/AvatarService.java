package com.polyethene.polyhouse.service;

import com.polyethene.polyhouse.model.dto.AvatarSubmitDTO;
import com.polyethene.polyhouse.model.vo.ProfileSubmissionVO;
import org.springframework.stereotype.Service;

/** 保留头像的独立入口，审核状态与数据库事务统一交给资料审核服务。 */
@Service
public class AvatarService {
    private final ProfileModerationService moderation;

    /** 审核服务复用现有身份、限额与审计规则。 */
    public AvatarService(ProfileModerationService moderation) {
        this.moderation = moderation;
    }

    /** 提交头像仅创建待审申请，已发布值仍由屋主批准后替换。 */
    public ProfileSubmissionVO upload(AvatarSubmitDTO request, String token) {
        return moderation.submitAvatar(request, token);
    }
}
