package com.polyethene.polyhouse.common.constant;

/** 审核状态机，只有仍有效的 PENDING 可以决定。 */
public enum ProfileReviewStatus {
    /** 等待屋主精确版本审核。 */
    PENDING,
    /** 保留通过事实，不表示当前仍在发布。 */
    APPROVED,
    /** 驳回不改变当前公开资料。 */
    REJECTED,
    /** 新申请取代旧待审，旧审批不能发布。 */
    SUPERSEDED,
    /** 七天待审窗口结束，私有图片可回收。 */
    EXPIRED,
    /** 账号失效或下架使旧待审不可发布。 */
    INVALIDATED
}
