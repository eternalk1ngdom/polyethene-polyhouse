package com.polyethene.polyhouse.model.vo;

import java.time.LocalDate;
import java.util.List;

/**
 * 当期日榜相对前 7 日已发布快照的异动汇总；无前序历史时日报中该字段为 null，前端优雅隐藏相关徽标。
 * @param previousDate 上一已发布日（北京时间），作为连榜、排名变化与退榜的直接对比基准日
 * @param lookbackDays 前 7 个自然日内实际参与对比的已发布日报天数（1～7）
 * @param newCount 当期新面孔总数（NEW + NEW_TOP）
 * @param newTopCount 当期空降前 5 数量（NEW_TOP）
 * @param returningCount 当期重回榜单数量（RETURNING）
 * @param streakCount 当期连榜数量（STREAK）
 * @param droppedItems 上一已发布日在榜、当期退榜的项目列表（按上期排名升序）
 */
public record TrendPresenceSummaryVO(
        LocalDate previousDate,
        int lookbackDays,
        int newCount,
        int newTopCount,
        int returningCount,
        int streakCount,
        List<TrendDroppedProjectVO> droppedItems) {
    /** 冻结退榜列表，防止调用方修改响应视图。 */
    public TrendPresenceSummaryVO {
        droppedItems = droppedItems == null ? List.of() : List.copyOf(droppedItems);
    }
}
