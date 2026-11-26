package com.polyethene.polyhouse.model.vo;

import com.fasterxml.jackson.annotation.JsonInclude;

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
 * @param insightComplete 所有需分析的项目是否有当前有效解读；陈旧或缺失时为 false
 * @param items 按来源排名排列的当期资料，最大二十五条
 * @param presenceSummary 当期相对前 7 日已发布快照的异动汇总；归档存储或无前序历史时为 null
 */
public record TrendReportVO(String reportId, String periodType, LocalDate periodKey, Instant capturedAt,
                            Instant publishedAt, String sourceUrl, boolean insightComplete,
                            List<TrendProjectVO> items,
                            @JsonInclude(JsonInclude.Include.NON_NULL) TrendPresenceSummaryVO presenceSummary) {
    /** 防止归档后调用方修改列表造成内存视图和保存内容不一致。 */
    public TrendReportVO {
        items = List.copyOf(items);
    }

    /** 保留既有归档与测试构造，异动汇总由查询层实时计算而不固化到历史 JSON。 */
    public TrendReportVO(String reportId, String periodType, LocalDate periodKey, Instant capturedAt,
                         Instant publishedAt, String sourceUrl, boolean insightComplete,
                         List<TrendProjectVO> items) {
        this(reportId, periodType, periodKey, capturedAt, publishedAt, sourceUrl, insightComplete, items, null);
    }

    /** 生成附加异动身份与汇总后的公开返回视图，不改变原始归档内容。 */
    public TrendReportVO withPresence(List<TrendProjectVO> enrichedItems, TrendPresenceSummaryVO summary) {
        return new TrendReportVO(reportId, periodType, periodKey, capturedAt, publishedAt,
                sourceUrl, insightComplete, enrichedItems, summary);
    }
}
