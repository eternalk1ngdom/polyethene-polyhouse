package com.polyethene.polyhouse.model.vo;

/**
 * 从任务实际发布的不可变版本统计。内容来源各算一次，拒收与待核对是另一组可能重叠的事实。
 * @param total 该版本收录项目数
 * @param generated 本次新生成并通过校验的篇数
 * @param reused 已核对仍有效的复用稿数
 * @param stale 保留原稿但未确认当前有效的篇数
 * @param skipped 按资料清单规则免分析的项目数
 * @param missing 没有完整正文且不属于免分析的项目数
 * @param rejected 明确记录输出未通过校验的项目数
 * @param pending 明确记录结果待核对的项目数，不能据此推算实际费用
 * @param readmeAvailable 本期取得 README 的仓库数，分母为总收录数
 * @param eligible 有仓库身份及当期 README 且需要解读的项目数，衡量材料覆盖
 * @param currentEligible 上述项目中已有本次新稿或核实有效的复用稿数
 * @param missingDescription 缺少正文的项目中连来源简介也为空的项数
 */
public record TrendPublicationSummaryVO(int total, int generated, int reused, int stale, int skipped, int missing,
                                        int rejected, int pending, int readmeAvailable, int eligible,
                                        int currentEligible, int missingDescription) {
    /** 旧版无单列原因时只采用其明确的状态；不能把保留了旧稿的拒收再计入缺文。 */
    public static TrendPublicationSummaryVO from(TrendReportVO report) {
        int generated = 0, reused = 0, stale = 0, skipped = 0, missing = 0, rejected = 0, pending = 0;
        int readmeAvailable = 0, eligible = 0, currentEligible = 0, missingDescription = 0;
        for (var item : report.items()) {
            String status = item.insightStatus();
            if ("NOT_REQUIRED".equals(status)) skipped++;
            else if (item.insight() == null) {
                missing++;
                if (item.description() == null || item.description().isBlank()) missingDescription++;
            }
            else if ("ANALYZED".equals(status)) generated++;
            else if ("REUSED".equals(status)) reused++;
            else if ("STALE".equals(status)) stale++;
            String reason = item.insightReason() == null ? status : item.insightReason();
            if ("INVALID_OUTPUT".equals(reason)) rejected++;
            if ("PENDING".equals(reason)) pending++;
            if ("AVAILABLE".equals(item.readmeStatus())) {
                readmeAvailable++;
                if (!"DOC_LIST".equals(item.repoType()) && item.repositoryId() != null) {
                    eligible++;
                    if (item.insight() != null && ("ANALYZED".equals(status) || "REUSED".equals(status))) currentEligible++;
                }
            }
        }
        return new TrendPublicationSummaryVO(report.items().size(), generated, reused, stale, skipped, missing, rejected,
                pending, readmeAvailable, eligible, currentEligible, missingDescription);
    }
}
