package com.polyethene.polyhouse.service.trend;

import com.polyethene.polyhouse.model.vo.TrendProjectVO;
import com.polyethene.polyhouse.model.vo.TrendReportVO;

import java.time.LocalDate;
import java.util.*;

/**
 * 星潮 7 日窗口共享的仓库身份归并与窗口切片规则（包内私有）。
 * 供 2A 确定性统计与 2B 风口简报输入组装共用，防止两处各自演进导致归并口径分叉。
 */
final class TrendWindowIdentity {
    private TrendWindowIdentity() { }

    /**
     * 按日期闭区间裁剪窗口内日报；若同日存在多份快照，仅保留采集时间最新的一份。
     */
    static Map<LocalDate, TrendReportVO> latestByDateInWindow(
            LocalDate anchorDate, LocalDate windowStart, List<TrendReportVO> reports) {
        Map<LocalDate, TrendReportVO> byDate = new HashMap<>();
        for (var report : reports) {
            if (report == null || report.periodKey() == null) continue;
            if (report.periodKey().isBefore(windowStart) || report.periodKey().isAfter(anchorDate)) continue;
            var existing = byDate.get(report.periodKey());
            if (existing == null || report.capturedAt().isAfter(existing.capturedAt())) {
                byDate.put(report.periodKey(), report);
            }
        }
        return byDate;
    }

    /**
     * 按锚点日向过去降序返回窗口内有效日报列表（首次出现的记录即为该仓库在窗口内的最新快照）。
     */
    static List<TrendReportVO> orderedReportsInWindow(
            LocalDate anchorDate, LocalDate windowStart, int windowDays, List<TrendReportVO> reports) {
        var byDate = latestByDateInWindow(anchorDate, windowStart, reports);
        List<TrendReportVO> ordered = new ArrayList<>();
        for (int offset = 0; offset < windowDays; offset++) {
            var report = byDate.get(anchorDate.minusDays(offset));
            if (report != null) ordered.add(report);
        }
        return ordered;
    }

    /**
     * 在窗口内建立「小写仓库全名 -> 稳定仓库 ID 集合」索引，供单日缺失元数据时安全回查唯一 ID。
     */
    static Map<String, Set<String>> indexRepositoryIds(List<TrendReportVO> orderedReports) {
        Map<String, Set<String>> idsByName = new HashMap<>();
        for (var report : orderedReports) {
            for (var item : report.items()) {
                if (validRepositoryId(item.repositoryId())) {
                    idsByName.computeIfAbsent(lowerName(item.fullName()), k -> new HashSet<>()).add(item.repositoryId());
                }
            }
        }
        return idsByName;
    }

    /** 校验 GitHub 数字稳定编号格式，避免非法字符串冒充已核实身份。 */
    static boolean validRepositoryId(String value) {
        return value != null && value.matches("[1-9][0-9]{0,18}");
    }

    /** 优先采用条目自身稳定 ID；缺失时仅在窗口内无歧义指向唯一 ID 时回查归并。 */
    static String resolveStableId(TrendProjectVO item, Map<String, Set<String>> idsByName) {
        if (validRepositoryId(item.repositoryId())) return item.repositoryId();
        var candidates = idsByName.get(lowerName(item.fullName()));
        return candidates != null && candidates.size() == 1 ? candidates.iterator().next() : null;
    }

    /** 构造窗口去重池键：有稳定 ID 按 ID 归并（支持改名合并与易主分离），否则按小写全名退化归并。 */
    static String repoKey(TrendProjectVO item, String resolvedId) {
        return resolvedId != null ? "id:" + resolvedId : "name:" + lowerName(item.fullName());
    }

    /** 提取受支持的 8 大类代号，未解读或非法分类返回 null。 */
    static String validCategory(TrendProjectVO item) {
        if (item.insight() == null || item.insight().category() == null) return null;
        return TrendInsightCodec.CATEGORIES.contains(item.insight().category()) ? item.insight().category() : null;
    }

    /** 使用根区域统一转为小写，避免系统默认 Locale 影响英文仓库名大小写折叠。 */
    static String lowerName(String fullName) {
        return fullName.toLowerCase(Locale.ROOT);
    }
}
