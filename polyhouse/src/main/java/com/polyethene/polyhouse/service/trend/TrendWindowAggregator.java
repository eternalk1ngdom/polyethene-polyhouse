package com.polyethene.polyhouse.service.trend;

import com.polyethene.polyhouse.model.vo.TrendProjectVO;
import com.polyethene.polyhouse.model.vo.TrendReportVO;
import com.polyethene.polyhouse.model.vo.TrendWindowStatsVO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * 7 日榜内风向确定性聚合器：无状态纯计算，不访问数据库、缓存或网络，所有指标均可逐项手算复核。
 */
public final class TrendWindowAggregator {
    /** 固定观察 7 个连续自然日（含锚点日），不回溯猜造未采集日期。 */
    public static final int WINDOW_DAYS = 7;
    private static final int MAX_SAMPLE_REPOS = 3;
    private static final int MAX_RECURRING_REPOS = 15;

    private TrendWindowAggregator() { }

    /**
     * 基于窗口内已发布的日报快照生成 7 日榜内风向统计。
     * @param anchorDate 观察窗口锚点日期（北京时间）
     * @param reports 窗口内已发布日报列表（调用方应已校验完整性）
     * @return 冻结的 7 日确定性统计结果
     */
    public static TrendWindowStatsVO aggregate(LocalDate anchorDate, List<TrendReportVO> reports) {
        Objects.requireNonNull(anchorDate, "anchorDate");
        Objects.requireNonNull(reports, "reports");
        LocalDate windowStart = anchorDate.minusDays(WINDOW_DAYS - 1);

        // 防御性按日期去重（若同日有多条记录，仅保留采集时间最新的一份）并限定在 7 日闭区间内。
        Map<LocalDate, TrendReportVO> byDate = new HashMap<>();
        for (var report : reports) {
            if (report == null || report.periodKey() == null) continue;
            if (report.periodKey().isBefore(windowStart) || report.periodKey().isAfter(anchorDate)) continue;
            var existing = byDate.get(report.periodKey());
            if (existing == null || report.capturedAt().isAfter(existing.capturedAt())) {
                byDate.put(report.periodKey(), report);
            }
        }
        var anchorReport = byDate.get(anchorDate);
        if (anchorReport == null) {
            throw new IllegalArgumentException("锚点日期缺少已发布日报: " + anchorDate);
        }

        List<LocalDate> coveredDates = new ArrayList<>();
        List<LocalDate> missingDates = new ArrayList<>();
        List<TrendReportVO> orderedReports = new ArrayList<>();
        for (int offset = 0; offset < WINDOW_DAYS; offset++) {
            LocalDate day = anchorDate.minusDays(offset);
            var report = byDate.get(day);
            if (report != null) {
                coveredDates.add(day);
                orderedReports.add(report);
            } else {
                missingDates.add(day);
            }
        }

        // 先在窗口内建立「仓库名 -> 稳定仓库 ID」映射，仅当名称无歧义指向唯一 ID 时，合并缺元数据的退化条目。
        Map<String, Set<String>> idsByName = new HashMap<>();
        for (var report : orderedReports) {
            for (var item : report.items()) {
                if (validRepositoryId(item.repositoryId())) {
                    idsByName.computeIfAbsent(lowerName(item.fullName()), k -> new HashSet<>()).add(item.repositoryId());
                }
            }
        }

        // 1. 锚点当日分母与分类计数
        int anchorClassified = 0;
        int anchorDocList = 0;
        int anchorMissingInsight = 0;
        Map<String, Integer> anchorCategoryCounts = new HashMap<>();
        for (var item : anchorReport.items()) {
            String category = validCategory(item);
            if (category != null) {
                anchorClassified++;
                anchorCategoryCounts.merge(category, 1, Integer::sum);
            } else if ("DOC_LIST".equals(item.repoType())) {
                anchorDocList++;
            } else {
                anchorMissingInsight++;
            }
        }
        var anchorSummary = new TrendWindowStatsVO.AnchorSummary(
                anchorReport.items().size(), anchorClassified, anchorDocList + anchorMissingInsight,
                anchorDocList, anchorMissingInsight);

        // 2. 7 日去重仓库池聚合（按日期降序遍历，首次出现即窗口内最新快照）
        Map<String, RepoAccumulator> repos = new LinkedHashMap<>();
        int totalAppearances = 0;
        for (var report : orderedReports) {
            LocalDate day = report.periodKey();
            for (var item : report.items()) {
                totalAppearances++;
                String resolvedId = resolveStableId(item, idsByName);
                String key = resolvedId != null ? "id:" + resolvedId : "name:" + lowerName(item.fullName());
                var acc = repos.computeIfAbsent(key, k -> new RepoAccumulator(resolvedId, item));
                acc.record(day, anchorDate, item);
            }
        }

        // 3. 锚点日之前的前序日期去重分类基准，用于计算 shareDelta（避免拿包含锚点日自身的数据自比）
        Map<String, Integer> previousCategoryCounts = new HashMap<>();
        int previousClassifiedCount = 0;
        if (orderedReports.size() >= 2) {
            Map<String, String> previousRepoCategory = new LinkedHashMap<>();
            for (int i = 1; i < orderedReports.size(); i++) {
                for (var item : orderedReports.get(i).items()) {
                    String resolvedId = resolveStableId(item, idsByName);
                    String key = resolvedId != null ? "id:" + resolvedId : "name:" + lowerName(item.fullName());
                    String category = validCategory(item);
                    if (category != null) {
                        previousRepoCategory.putIfAbsent(key, category);
                    }
                }
            }
            for (String category : previousRepoCategory.values()) {
                previousClassifiedCount++;
                previousCategoryCounts.merge(category, 1, Integer::sum);
            }
        }

        // 4. 汇总 7 日去重分母与分类桶
        int classifiedRepos = 0;
        int docListRepos = 0;
        int missingInsightRepos = 0;
        int starsKnownRepos = 0;
        int staleClassifiedRepos = 0;
        Instant oldestStaleAt = null;
        int unverifiedIdentities = 0;
        int radar3ReadyCount = 0;
        Map<String, List<RepoAccumulator>> byCategory = new HashMap<>();

        for (var repo : repos.values()) {
            if (!repo.identityVerified()) unverifiedIdentities++;
            if (repo.latestStarsAdded != null) starsKnownRepos++;
            if (repo.category != null) {
                classifiedRepos++;
                byCategory.computeIfAbsent(repo.category, k -> new ArrayList<>()).add(repo);
                if (repo.classifiedFromStale) {
                    staleClassifiedRepos++;
                    oldestStaleAt = earlier(oldestStaleAt, repo.classifiedAnalyzedAt);
                }
                if (repo.radar3Ready) radar3ReadyCount++;
            } else if ("DOC_LIST".equals(repo.latestRepoType)) {
                docListRepos++;
            } else {
                missingInsightRepos++;
            }
        }

        var windowSummary = new TrendWindowStatsVO.WindowSummary(
                repos.size(), totalAppearances, classifiedRepos, docListRepos + missingInsightRepos,
                docListRepos, missingInsightRepos, starsKnownRepos, staleClassifiedRepos, oldestStaleAt,
                unverifiedIdentities, radar3ReadyCount);

        // 5. 生成 8 大类统计（仅返回窗口内有上榜仓库的分类，保持结果紧凑）
        List<TrendWindowStatsVO.CategoryStat> categories = new ArrayList<>();
        for (var entry : byCategory.entrySet()) {
            String category = entry.getKey();
            var members = entry.getValue();
            int anchorCount = anchorCategoryCounts.getOrDefault(category, 0);
            Double anchorShare = share(anchorCount, anchorClassified);
            int windowUnique = members.size();
            int windowApps = members.stream().mapToInt(RepoAccumulator::appearanceDays).sum();
            Double windowShare = share(windowUnique, classifiedRepos);
            Double shareDelta = (orderedReports.size() >= 2 && anchorClassified > 0 && previousClassifiedCount > 0)
                    ? delta(anchorCount, anchorClassified, previousCategoryCounts.getOrDefault(category, 0), previousClassifiedCount)
                    : null;

            // 缺失涨星严格不计零，只将非空最新期涨星纳入去重仓库口径中位数计算。
            List<Long> categoryStars = new ArrayList<>();
            int catStaleCount = 0;
            Instant catOldestStale = null;
            for (var member : members) {
                if (member.latestStarsAdded != null) categoryStars.add(member.latestStarsAdded);
                if (member.classifiedFromStale) {
                    catStaleCount++;
                    catOldestStale = earlier(catOldestStale, member.classifiedAnalyzedAt);
                }
            }
            Long maxStars = categoryStars.isEmpty() ? null : Collections.max(categoryStars);
            Double medianStars = median(categoryStars);

            var samples = members.stream()
                    .sorted(Comparator.comparingInt(RepoAccumulator::appearanceDays).reversed()
                            .thenComparing(Comparator.comparing((RepoAccumulator r) -> r.inAnchorDay).reversed())
                            .thenComparingInt(r -> r.latestRank)
                            .thenComparing(r -> r.fullName, String.CASE_INSENSITIVE_ORDER))
                    .limit(MAX_SAMPLE_REPOS)
                    .map(RepoAccumulator::toSample)
                    .toList();

            categories.add(new TrendWindowStatsVO.CategoryStat(
                    category, anchorCount, anchorShare, windowUnique, windowApps, windowShare,
                    shareDelta, categoryStars.size(), medianStars, maxStars, catStaleCount, catOldestStale, samples));
        }
        categories.sort(Comparator.comparingInt(TrendWindowStatsVO.CategoryStat::windowUniqueCount).reversed()
                .thenComparing(Comparator.comparingInt(TrendWindowStatsVO.CategoryStat::anchorDayCount).reversed())
                .thenComparing(Comparator.comparingInt(TrendWindowStatsVO.CategoryStat::windowAppearances).reversed())
                .thenComparing(TrendWindowStatsVO.CategoryStat::category));

        // 6. 生成多日上榜仓库列表（>= 2 天）
        List<TrendWindowStatsVO.RecurringRepo> recurring = repos.values().stream()
                .filter(repo -> repo.appearanceDays() >= 2)
                .map(repo -> repo.toRecurring(anchorDate))
                .sorted(Comparator.comparingInt(TrendWindowStatsVO.RecurringRepo::appearanceDays).reversed()
                        .thenComparing(Comparator.comparingInt(TrendWindowStatsVO.RecurringRepo::consecutiveDays).reversed())
                        .thenComparing(Comparator.comparingInt(TrendWindowStatsVO.RecurringRepo::anchorStreakDays).reversed())
                        .thenComparingInt(TrendWindowStatsVO.RecurringRepo::latestRank)
                        .thenComparing(TrendWindowStatsVO.RecurringRepo::fullName, String.CASE_INSENSITIVE_ORDER))
                .limit(MAX_RECURRING_REPOS)
                .toList();

        int coveredDays = coveredDates.size();
        String sampleStatus = coveredDays < 3 ? "INSUFFICIENT" : coveredDays < 5 ? "LIMITED" : "SUFFICIENT";
        return new TrendWindowStatsVO("DAILY", anchorDate, windowStart, anchorDate, WINDOW_DAYS, coveredDays,
                coveredDates, missingDates, sampleStatus, anchorSummary, windowSummary, categories, recurring);
    }

    private static boolean validRepositoryId(String value) {
        return value != null && value.matches("[1-9][0-9]{0,18}");
    }

    private static String resolveStableId(TrendProjectVO item, Map<String, Set<String>> idsByName) {
        if (validRepositoryId(item.repositoryId())) return item.repositoryId();
        var candidates = idsByName.get(lowerName(item.fullName()));
        return candidates != null && candidates.size() == 1 ? candidates.iterator().next() : null;
    }

    private static String validCategory(TrendProjectVO item) {
        if (item.insight() == null || item.insight().category() == null) return null;
        return TrendInsightCodec.CATEGORIES.contains(item.insight().category()) ? item.insight().category() : null;
    }

    private static String lowerName(String fullName) {
        return fullName.toLowerCase(Locale.ROOT);
    }

    private static Instant earlier(Instant current, Instant candidate) {
        if (candidate == null) return current;
        return current == null || candidate.isBefore(current) ? candidate : current;
    }

    private static Double share(int numerator, int denominator) {
        if (denominator <= 0) return null;
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private static Double delta(int anchorCount, int anchorDenom, int prevCount, int prevDenom) {
        // 先以高精度计算占比差值再四舍五入，避免两侧各自舍入叠加误差或浮点尾数噪声。
        BigDecimal anchor = BigDecimal.valueOf(anchorCount).divide(BigDecimal.valueOf(anchorDenom), 8, RoundingMode.HALF_UP);
        BigDecimal prev = BigDecimal.valueOf(prevCount).divide(BigDecimal.valueOf(prevDenom), 8, RoundingMode.HALF_UP);
        return anchor.subtract(prev).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    /** 缺失值不参与计算；奇数取中位值，偶数取中间两数平均值。 */
    static Double median(List<Long> values) {
        if (values == null || values.isEmpty()) return null;
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int size = sorted.size();
        int mid = size / 2;
        if (size % 2 == 1) return (double) sorted.get(mid);
        return (sorted.get(mid - 1) + sorted.get(mid)) / 2.0;
    }

    /** 计算降序不重复日期序列中的最长连续自然日跨度，缺采日自然断开连榜。 */
    static int longestConsecutiveDays(List<LocalDate> descDates) {
        if (descDates.isEmpty()) return 0;
        int best = 1;
        int current = 1;
        for (int i = 1; i < descDates.size(); i++) {
            if (descDates.get(i).plusDays(1).equals(descDates.get(i - 1))) {
                current++;
                if (current > best) best = current;
            } else {
                current = 1;
            }
        }
        return best;
    }

    /** 计算截至锚点日的连续自然日上榜天数；若锚点日未上榜则为 0。 */
    static int anchorStreakDays(List<LocalDate> descDates, LocalDate anchorDate) {
        int streak = 0;
        LocalDate expected = anchorDate;
        for (var day : descDates) {
            if (day.equals(expected)) {
                streak++;
                expected = expected.minusDays(1);
            } else if (day.isBefore(expected)) {
                break;
            }
        }
        return streak;
    }

    private static final class RepoAccumulator {
        private final String repositoryId;
        private final String fullName;
        private final String repoUrl;
        private final int latestRank;
        private final String latestRepoType;
        private final String latestInsightStatus;
        private final List<LocalDate> appearedDates = new ArrayList<>();
        private final List<Long> starsHistory = new ArrayList<>();
        private boolean inAnchorDay;
        private int bestRank;
        private Long latestStarsAdded;
        private String category;
        private String definition;
        private String classifiedStatus;
        private Instant classifiedAnalyzedAt;
        private boolean classifiedFromStale;
        private boolean radar3Ready;

        private RepoAccumulator(String repositoryId, TrendProjectVO latestSnapshot) {
            this.repositoryId = repositoryId;
            this.fullName = latestSnapshot.fullName();
            this.repoUrl = latestSnapshot.repoUrl();
            this.latestRank = latestSnapshot.rankNum();
            this.bestRank = latestSnapshot.rankNum();
            this.latestRepoType = latestSnapshot.repoType();
            this.latestInsightStatus = latestSnapshot.insightStatus();
        }

        private void record(LocalDate day, LocalDate anchorDate, TrendProjectVO item) {
            if (appearedDates.isEmpty() || !appearedDates.getLast().equals(day)) {
                appearedDates.add(day);
            }
            if (day.equals(anchorDate)) inAnchorDay = true;
            if (item.rankNum() < bestRank) bestRank = item.rankNum();
            if (item.starsAdded() != null && item.starsAdded() >= 0) {
                starsHistory.add(item.starsAdded());
                if (latestStarsAdded == null) latestStarsAdded = item.starsAdded();
            }
            String validCat = validCategory(item);
            if (category == null && validCat != null) {
                category = validCat;
                definition = item.insight().definition();
                classifiedStatus = item.insightStatus();
                classifiedAnalyzedAt = item.insight().analyzedAt();
                classifiedFromStale = "STALE".equals(item.insightStatus());
                radar3Ready = TrendInsightCodec.RULE.equals(item.insight().ruleVersion())
                        && item.insight().trendSignal() != null && !item.insight().trendSignal().isBlank();
            }
        }

        private boolean identityVerified() { return repositoryId != null; }
        private int appearanceDays() { return appearedDates.size(); }

        private TrendWindowStatsVO.SampleRepo toSample() {
            return new TrendWindowStatsVO.SampleRepo(repositoryId, identityVerified(), fullName, repoUrl,
                    inAnchorDay, appearanceDays(), latestRank, latestStarsAdded);
        }

        private TrendWindowStatsVO.RecurringRepo toRecurring(LocalDate anchorDate) {
            return new TrendWindowStatsVO.RecurringRepo(repositoryId, identityVerified(), fullName, repoUrl,
                    inAnchorDay, category, definition, appearanceDays(),
                    longestConsecutiveDays(appearedDates), anchorStreakDays(appearedDates, anchorDate),
                    latestRank, bestRank, latestStarsAdded, median(starsHistory), appearedDates,
                    classifiedStatus != null ? classifiedStatus : latestInsightStatus, classifiedAnalyzedAt);
        }
    }
}
