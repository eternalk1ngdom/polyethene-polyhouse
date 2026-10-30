package com.polyethene.polyhouse.model.entity;
import com.polyethene.polyhouse.common.constant.*;
import java.time.LocalDateTime;
/** 仅在服务层使用的申请记录，私有键和内容指纹绝不随 Entity 直接返回。 */
public record ProfileSubmission(long id,long userId,String username,ProfileField kind,ProfileReviewStatus status,
        long version,String requestId,String proposedValue,String contentHash,String privateKey,String reason,
        LocalDateTime createdAt,LocalDateTime expiresAt,LocalDateTime reviewedAt,Long reviewerId,long credentialVersion) { }
