package com.polyethene.polyhouse.common.constant;

/** 已发布资料处置，不回写历史审批结果。 */
public enum ProfilePublicAction {
    /** 屋主显式确认历史公开内容。 */
    KEEP,
    /** 下架当前内容，恢复默认展示。 */
    TAKEDOWN
}
