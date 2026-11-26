package com.polyethene.polyhouse.service.trend;

import com.polyethene.polyhouse.model.vo.TrendDroppedProjectVO;
import com.polyethene.polyhouse.model.vo.TrendPresenceSummaryVO;
import com.polyethene.polyhouse.model.vo.TrendPresenceVO;
import com.polyethene.polyhouse.model.vo.TrendProjectVO;
import com.polyethene.polyhouse.model.vo.TrendReportVO;

import java.time.LocalDate;
import java.util.*;

/**
 * 日报排行榜异动身份纯计算器：基于前 7 个自然日内已发布日报快照确定性计算新面孔、空降前 5、重回、连榜与昨日退榜。
 * 复用 {@link TrendWindowIdentity} 稳定仓库 ID 归并规则，防止仓库改名或单日缺元数据造成误判。
 */
public final class TrendPresenceCalculator {
    /** 向前观察的自然日天数（不含当期日，即 [periodKey - 7, periodKey - 1]）。 */
    public static final int LOOKBACK_DAYS = 7;
    /** 空降前排的最高排名阈值（1～5 名触发 NEW_TOP）。 */
    public static final int TOP_RANK_THRESHOLD = 5;

    public static final String STATUS_NEW = "NEW";
    public static final String STATUS_NEW_TOP = "NEW_TOP";
    public static final String STATUS_RETURNING = "RETURNING";
    public static final String STATUS_STREAK = "STREAK";

    private TrendPresenceCalculator() { }

    /**
     * 为当期日报附加榜上身份信号与异动汇总；前 7 日无已发布日报时原样返回（不误标新上榜）。
     * @param current 当期已发布日报
     * @param priorCandidates 前 7 个自然日内的历史日报候选列表
     * @return 附加异动字段后的只读日报视图；无基线时保持 presence 与 presenceSummary 为 null
     */
    public static TrendReportVO enrich(TrendReportVO current, List<TrendReportVO> priorCandidates) {
        Objects.requireNonNull(current, "current");
        if (priorCandidates == null || priorCandidates.isEmpty()) {
            return current;
        }

        LocalDate anchor = current.periodKey();
        LocalDate priorEnd = anchor.minusDays(1);
        LocalDate priorStart = anchor.minusDays(LOOKBACK_DAYS);
        // 复用窗口切片与同日去重规则，按日期降序排列前 7 个自然日内的有效已发布日报。
        List<TrendReportVO> priorReports = TrendWindowIdentity.orderedReportsInWindow(
                priorEnd, priorStart, LOOKBACK_DAYS, priorCandidates);
        if (priorReports.isEmpty()) {
            return current;
        }

        // 将当期与前序合并建索引，确保单日缺少 repositoryId 时可借助窗口内唯一稳定 ID 安全归并。
        List<TrendReportVO> allOrdered = new ArrayList<>(priorReports.size() + 1);
        allOrdered.add(current);
        allOrdered.addAll(priorReports);
        Map<String, Set<String>> idsByName = TrendWindowIdentity.indexRepositoryIds(allOrdered);

        // 逐期构建「仓库归并键 -> 当期排名」，供连榜天数与历史出现检查 O(1) 查询。
        List<Map<String, Integer>> priorRankMaps = new ArrayList<>(priorReports.size());
        Set<String> seenInLookback = new HashSet<>();
        for (var report : priorReports) {
            Map<String, Integer> rankByKey = new HashMap<>();
            for (var item : report.items()) {
                String key = repoKey(item, idsByName);
                rankByKey.putIfAbsent(key, item.rankNum());
                seenInLookback.add(key);
            }
            priorRankMaps.add(rankByKey);
        }

        Map<String, Integer> previousRanks = priorRankMaps.getFirst();
        Set<String> currentKeys = new HashSet<>();
        List<TrendProjectVO> enrichedItems = new ArrayList<>(current.items().size());

        int newCount = 0;
        int newTopCount = 0;
        int returningCount = 0;
        int streakCount = 0;

        for (var item : current.items()) {
            String key = repoKey(item, idsByName);
            currentKeys.add(key);

            TrendPresenceVO presence;
            Integer previousRank = previousRanks.get(key);
            if (previousRank != null) {
                int streakDays = 1;
                for (var rankMap : priorRankMaps) {
                    if (rankMap.containsKey(key)) {
                        streakDays++;
                    } else {
                        break;
                    }
                }
                int rankDelta = previousRank - item.rankNum();
                presence = new TrendPresenceVO(STATUS_STREAK, streakDays, previousRank, rankDelta);
                streakCount++;
            } else if (seenInLookback.contains(key)) {
                presence = new TrendPresenceVO(STATUS_RETURNING, 1, null, null);
                returningCount++;
            } else if (item.rankNum() <= TOP_RANK_THRESHOLD) {
                presence = new TrendPresenceVO(STATUS_NEW_TOP, 1, null, null);
                newCount++;
                newTopCount++;
            } else {
                presence = new TrendPresenceVO(STATUS_NEW, 1, null, null);
                newCount++;
            }
            enrichedItems.add(item.withPresence(presence));
        }

        // 昨日（上一已发布日）在榜、今日退榜的项目按上期排名升序保留，供底部折叠区速览。
        TrendReportVO previousReport = priorReports.getFirst();
        List<TrendDroppedProjectVO> droppedItems = new ArrayList<>();
        Set<String> addedDropped = new HashSet<>();
        for (var prevItem : previousReport.items()) {
            String key = repoKey(prevItem, idsByName);
            if (!currentKeys.contains(key) && addedDropped.add(key)) {
                droppedItems.add(new TrendDroppedProjectVO(
                        prevItem.rankNum(),
                        prevItem.repositoryId(),
                        prevItem.fullName(),
                        prevItem.repoUrl(),
                        prevItem.primaryLanguage(),
                        TrendWindowIdentity.validCategory(prevItem),
                        prevItem.starsAdded()));
            }
        }
        droppedItems.sort(Comparator.comparingInt(TrendDroppedProjectVO::previousRank));

        var summary = new TrendPresenceSummaryVO(
                previousReport.periodKey(),
                priorReports.size(),
                newCount,
                newTopCount,
                returningCount,
                streakCount,
                droppedItems);
        return current.withPresence(enrichedItems, summary);
    }

    private static String repoKey(TrendProjectVO item, Map<String, Set<String>> idsByName) {
        String resolvedId = TrendWindowIdentity.resolveStableId(item, idsByName);
        return TrendWindowIdentity.repoKey(item, resolvedId);
    }
}
