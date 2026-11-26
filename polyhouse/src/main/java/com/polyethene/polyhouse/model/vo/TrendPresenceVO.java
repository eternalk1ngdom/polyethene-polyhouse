package com.polyethene.polyhouse.model.vo;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 单个项目在当期日榜的异动身份信号，由前 7 日已发布快照实时确定性计算，不改写不可变归档 JSON。
 * @param status 榜上身份：NEW（新上榜）、NEW_TOP（空降前 5）、RETURNING（重回榜单）、STREAK（连榜）
 * @param streakDays 截至当期连续上榜的已发布天数（至少为 1）
 * @param previousRank 上一已发布日在榜时的排名；非连榜为 null
 * @param rankDelta 相对上一已发布日的排名变化（正数表示上升，负数表示下降，0 表示持平）；非连榜为 null
 */
public record TrendPresenceVO(
        String status,
        int streakDays,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer previousRank,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer rankDelta) {
}
