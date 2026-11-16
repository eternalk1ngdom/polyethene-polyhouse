package com.polyethene.polyhouse.model.vo;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 一次已发布版本的完整只读快照，资料随版本冻结，后续解读更新不能改写历史。
 * @param reportId 版本 UUID，与未来前端的数字精度无关
 * @param periodType 固定 DAILY，首批不承诺其他周期
 * @param periodKey 实际采集开始时的北京时间日期
 * @param capturedAt 实际采集开始时间，ISO UTC 时间带时区
 * @param publishedAt 本版本发布时间
 * @param sourceUrl 固定的日榜来源
 * @param insightComplete 所有需要分析的项目是否完成；本批基础日报明确为 false
 * @param items 按来源排名排列的当期资料，最大二十五条
 */
public record TrendReportVO(String reportId, String periodType, LocalDate periodKey, Instant capturedAt,
                            Instant publishedAt, String sourceUrl, boolean insightComplete,
                            List<TrendProjectVO> items) {
    /** 防止归档后调用方修改列表造成内存视图和保存内容不一致。 */
    public TrendReportVO {
        items = List.copyOf(items);
    }
}
