package com.polyethene.polyhouse.model.vo;
import com.polyethene.polyhouse.common.constant.*;
import com.polyethene.polyhouse.model.entity.ProfileSubmission;
import java.time.LocalDateTime;
/** 审核申请最小投影，不返回私有磁盘路径和内容指纹。
 * @param id 申请编号字符串
 * @param userId 所属账号编号
 * @param username 核对申请人的登录名
 * @param kind 字段种类
 * @param status 申请状态
 * @param version 精确审核版本
 * @param requestId 提交防重编号
 * @param proposedValue 昵称申请纯文本，头像固定 null
 * @param reason 驳回/失效等必要说明
 * @param createdAt 提交时间（上海）
 * @param expiresAt 待审有效期
 * @param remainingToday 当日相应字段剩余额度；已入库申请计次，不因驳回/替代退回
 */
public record ProfileSubmissionVO(String id,String userId,String username,ProfileField kind,ProfileReviewStatus status,
        String version,String requestId,String proposedValue,String reason,LocalDateTime createdAt,
        LocalDateTime expiresAt,int remainingToday) {
    /** 只有受控图片接口可读取私有键，普通 JSON 永远不能带出文件地址。 */
    public static ProfileSubmissionVO from(ProfileSubmission s,int remaining) { return new ProfileSubmissionVO(
        Long.toString(s.id()),Long.toString(s.userId()),s.username(),s.kind(),s.status(),Long.toString(s.version()),
        s.requestId(),s.proposedValue(),s.reason(),s.createdAt(),s.expiresAt(),remaining); }
}
