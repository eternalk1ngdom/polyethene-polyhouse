package com.polyethene.polyhouse.model.vo;

import java.time.Instant;

/**
 * 待核对列表仅供屋主；金额统一人民币微元，大整数身份以字符串返回，同时兼容单仓解读与 7 日风口简报。
 * @param requestId 应用内AI请求UUID，不承诺能自动匹配平台原始请求
 * @param repositoryId 单仓请求为 GitHub 稳定编号字符串；简报请求为 briefing:YYYY-MM-DD 标识（向后兼容旧前端）
 * @param state RESERVED或UNKNOWN，二者都不能自动退额或重发
 * @param reservedMicros 本笔仍持有的预算，关闭时全额计入已用
 * @param createdAt 原预留时间
 * @param chargeKind 计费来源类型：REPO（单仓解读）或 BRIEFING（7 日风口简报）
 */
public record TrendPendingChargeVO(String requestId, String repositoryId, String state, long reservedMicros,
                                   Instant createdAt, String chargeKind) {
    /** 兼容既有单仓五参数构造调用，默认归类为 REPO。 */
    public TrendPendingChargeVO(String requestId, String repositoryId, String state, long reservedMicros,
                                Instant createdAt) {
        this(requestId, repositoryId, state, reservedMicros, createdAt, "REPO");
    }
}
