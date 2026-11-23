package com.polyethene.polyhouse.service.trend;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.model.vo.TrendProjectVO;
import com.polyethene.polyhouse.model.vo.TrendReportVO;
import com.polyethene.polyhouse.model.vo.TrendWindowStatsVO;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.*;

/**
 * 7 日风口简报无状态输入组装器：复用 2A 确定性统计，筛选有界候选仓库池，校验样本硬门槛并计算确定性 SHA-256 指纹。
 * 不访问数据库、缓存或网络，便于单测离线复核。
 */
public final class TrendBriefingInputBuilder {
    /** 触发简报生成的最低有效日报覆盖天数（3～4 天标为有限样本，5 天及以上标为充分样本）。 */
    public static final int MIN_COVERED_DAYS = 3;
    /** 触发简报生成的最低 radar-3/radar-4 具备风向观察（trendSignal）的独立仓库数。 */
    public static final int MIN_RADAR3_READY_COUNT = 5;
    /** 候选仓库池硬上限，避免提示词膨胀导致费用不可控。 */
    public static final int MAX_CANDIDATE_REPOS = 45;
    /** 用户材料在 JSON 字符串转义后的最大 UTF-8 字节预算，为系统提示词留足空间以确保总请求体严格 <= 24 KiB。 */
    public static final int MAX_ESCAPED_MATERIAL_BYTES = 16_000;
    /** 指纹口径版本；输入结构变化时更新，确保旧简报自动识别为已过期。 */
    public static final String FINGERPRINT_VERSION = "briefing-input-1";

    private static final int MAX_DEFINITION_SNIPPET = 100;
    private static final int MAX_TREND_SIGNAL_SNIPPET = 220;
    private static final ObjectMapper CANONICAL_JSON = new ObjectMapper();

    private TrendBriefingInputBuilder() { }

    /**
     * 基于窗口内已发布日报快照组装简报输入、白名单与确定性指纹；即使未达门槛也返回可诊断结果，便于发布台展示原因。
     * @param anchorDate 观察窗口锚点日期（北京时间）
     * @param reports 窗口内已发布日报列表
     * @return 冻结的简报输入快照与门槛评估结果
     */
    public static BriefingInput build(LocalDate anchorDate, List<TrendReportVO> reports) {
        var stats = TrendWindowAggregator.aggregate(anchorDate, reports);
        var orderedReports = orderedReportsInWindow(anchorDate, stats.windowStart(), reports);

        // 与 2A 保持完全一致的稳定 ID 归并规则，避免改名或单日缺元数据导致同一仓库在候选池重复出现。
        Map<String, Set<String>> idsByName = new HashMap<>();
        for (var report : orderedReports) {
            for (var item : report.items()) {
                if (validRepositoryId(item.repositoryId())) {
                    idsByName.computeIfAbsent(lowerName(item.fullName()), k -> new HashSet<>()).add(item.repositoryId());
                }
            }
        }

        Map<String, CandidateAccumulator> repos = new LinkedHashMap<>();
        for (var report : orderedReports) {
            LocalDate day = report.periodKey();
            for (var item : report.items()) {
                String resolvedId = resolveStableId(item, idsByName);
                String key = resolvedId != null ? "id:" + resolvedId : "name:" + lowerName(item.fullName());
                var acc = repos.computeIfAbsent(key, k -> new CandidateAccumulator(resolvedId, item));
                acc.record(day, anchorDate, item);
            }
        }

        // 候选池仅收录具备合法分类与一句话导语的代码项目，按「有 trendSignal -> 多日上榜 -> 当期在榜 -> 涨星高 -> 排名靠前 -> 名称字典序」排序。
        List<CandidateRepo> sortedAll = repos.values().stream()
                .filter(CandidateAccumulator::isCandidate)
                .map(CandidateAccumulator::toCandidate)
                .sorted(Comparator.comparing(CandidateRepo::hasTrendSignal).reversed()
                        .thenComparing(Comparator.comparingInt(CandidateRepo::appearanceDays).reversed())
                        .thenComparing(Comparator.comparing(CandidateRepo::inAnchorDay).reversed())
                        .thenComparing(CandidateRepo::latestStarsAdded, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparingInt(CandidateRepo::latestRank)
                        .thenComparing(CandidateRepo::fullName, String.CASE_INSENSITIVE_ORDER))
                .toList();

        // 防御性按小写全名去重（极端仓库易主重名场景保留优先级更高的条目），确保实名引用白名单映射唯一无歧义。
        List<CandidateRepo> uniqueByName = new ArrayList<>();
        Set<String> seenLowerNames = new HashSet<>();
        for (var candidate : sortedAll) {
            if (seenLowerNames.add(lowerName(candidate.fullName()))) {
                uniqueByName.add(candidate);
            }
        }

        // 先构造窗口固定摘要，再按优先级逐条装入候选仓库，同时满足「最多 45 条」与「转义后用户材料 <= 16 KiB」双硬界。
        Map<String, Object> baseMaterial = buildBaseMaterial(stats);
        List<CandidateRepo> selected = new ArrayList<>();
        List<Map<String, Object>> candidatePayload = new ArrayList<>();
        String materialJson = writeCanonicalMaterial(baseMaterial, candidatePayload);

        for (var candidate : uniqueByName) {
            if (selected.size() >= MAX_CANDIDATE_REPOS) break;
            var entry = candidateToMap(candidate);
            candidatePayload.add(entry);
            String attempt = writeCanonicalMaterial(baseMaterial, candidatePayload);
            if (escapedJsonStringBytes(attempt) > MAX_ESCAPED_MATERIAL_BYTES) {
                candidatePayload.removeLast();
                break;
            }
            selected.add(candidate);
            materialJson = attempt;
        }

        Map<String, String> allowedByLower = new LinkedHashMap<>();
        Map<String, CandidateRepo> candidateByLower = new LinkedHashMap<>();
        for (var candidate : selected) {
            String key = lowerName(candidate.fullName());
            allowedByLower.put(key, candidate.fullName());
            candidateByLower.put(key, candidate);
        }

        int coveredDays = stats.coveredDays();
        int radar3ReadyCount = stats.windowSummary().radar3ReadyCount();
        String ineligibleReason = null;
        if (coveredDays < MIN_COVERED_DAYS) {
            ineligibleReason = "INSUFFICIENT_COVERED_DAYS";
        } else if (radar3ReadyCount < MIN_RADAR3_READY_COUNT) {
            ineligibleReason = "INSUFFICIENT_RADAR3_READY";
        } else if (selected.size() < 2) {
            ineligibleReason = "INSUFFICIENT_CANDIDATES";
        }

        String fingerprint = sha256Hex(FINGERPRINT_VERSION + "\n" + materialJson);
        return new BriefingInput(stats, selected, allowedByLower, candidateByLower, materialJson,
                fingerprint, ineligibleReason == null, ineligibleReason);
    }

    private static List<TrendReportVO> orderedReportsInWindow(LocalDate anchorDate, LocalDate windowStart, List<TrendReportVO> reports) {
        Map<LocalDate, TrendReportVO> byDate = new HashMap<>();
        for (var report : reports) {
            if (report == null || report.periodKey() == null) continue;
            if (report.periodKey().isBefore(windowStart) || report.periodKey().isAfter(anchorDate)) continue;
            var existing = byDate.get(report.periodKey());
            if (existing == null || report.capturedAt().isAfter(existing.capturedAt())) {
                byDate.put(report.periodKey(), report);
            }
        }
        List<TrendReportVO> ordered = new ArrayList<>();
        for (int offset = 0; offset < TrendWindowAggregator.WINDOW_DAYS; offset++) {
            var report = byDate.get(anchorDate.minusDays(offset));
            if (report != null) ordered.add(report);
        }
        return ordered;
    }

    private static Map<String, Object> buildBaseMaterial(TrendWindowStatsVO stats) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("anchorDate", stats.anchorDate().toString());
        root.put("windowStart", stats.windowStart().toString());
        root.put("windowEnd", stats.windowEnd().toString());
        root.put("coveredDays", stats.coveredDays());
        root.put("missingDates", stats.missingDates().stream().map(LocalDate::toString).toList());
        root.put("sampleStatus", stats.sampleStatus());
        root.put("uniqueRepoCount", stats.windowSummary().uniqueRepoCount());
        root.put("classifiedRepoCount", stats.windowSummary().classifiedRepoCount());
        root.put("radar3ReadyCount", stats.windowSummary().radar3ReadyCount());

        List<Map<String, Object>> categorySummary = new ArrayList<>();
        for (var cat : stats.categories()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("category", cat.category());
            item.put("windowUniqueCount", cat.windowUniqueCount());
            item.put("windowShare", cat.windowShare());
            item.put("anchorDayCount", cat.anchorDayCount());
            item.put("shareDelta", cat.shareDelta());
            item.put("medianStarsAdded", cat.medianStarsAdded());
            categorySummary.add(item);
        }
        root.put("categorySummary", categorySummary);

        List<Map<String, Object>> recurringSummary = new ArrayList<>();
        for (var rec : stats.recurringRepos()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("fullName", rec.fullName());
            item.put("category", rec.category());
            item.put("appearanceDays", rec.appearanceDays());
            item.put("consecutiveDays", rec.consecutiveDays());
            item.put("anchorStreakDays", rec.anchorStreakDays());
            recurringSummary.add(item);
        }
        root.put("recurringRepos", recurringSummary);
        return root;
    }

    private static Map<String, Object> candidateToMap(CandidateRepo candidate) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("fullName", candidate.fullName());
        item.put("category", candidate.category());
        item.put("appearanceDays", candidate.appearanceDays());
        item.put("latestStarsAdded", candidate.latestStarsAdded());
        item.put("definition", truncate(candidate.definition(), MAX_DEFINITION_SNIPPET));
        item.put("trendSignal", truncate(candidate.trendSignal(), MAX_TREND_SIGNAL_SNIPPET));
        return item;
    }

    private static String writeCanonicalMaterial(Map<String, Object> baseMaterial, List<Map<String, Object>> candidates) {
        Map<String, Object> full = new LinkedHashMap<>(baseMaterial);
        full.put("candidates", candidates);
        try {
            return CANONICAL_JSON.writeValueAsString(full);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("无法序列化简报候选材料", failure);
        }
    }

    private static int escapedJsonStringBytes(String raw) {
        try {
            return CANONICAL_JSON.writeValueAsBytes(raw).length;
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("无法计算简报材料字节数", failure);
        }
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >>> 4) & 0xf, 16));
                hex.append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("JVM 缺少 SHA-256 算法", failure);
        }
    }

    private static String truncate(String text, int maxChars) {
        if (text == null) return null;
        String clean = text.strip();
        if (clean.isEmpty()) return null;
        return clean.length() <= maxChars ? clean : clean.substring(0, maxChars);
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

    private static final class CandidateAccumulator {
        private final String repositoryId;
        private final String fullName;
        private final String repoUrl;
        private final int latestRank;
        private final List<LocalDate> appearedDates = new ArrayList<>();
        private boolean inAnchorDay;
        private Long latestStarsAdded;
        private String category;
        private String definition;
        private String trendSignal;

        private CandidateAccumulator(String repositoryId, TrendProjectVO latestSnapshot) {
            this.repositoryId = repositoryId;
            this.fullName = latestSnapshot.fullName();
            this.repoUrl = latestSnapshot.repoUrl();
            this.latestRank = latestSnapshot.rankNum();
        }

        private void record(LocalDate day, LocalDate anchorDate, TrendProjectVO item) {
            if (appearedDates.isEmpty() || !appearedDates.getLast().equals(day)) {
                appearedDates.add(day);
            }
            if (day.equals(anchorDate)) inAnchorDay = true;
            if (item.starsAdded() != null && item.starsAdded() >= 0 && latestStarsAdded == null) {
                latestStarsAdded = item.starsAdded();
            }
            String validCat = validCategory(item);
            if (category == null && validCat != null) {
                category = validCat;
                definition = item.insight().definition();
                if (TrendInsightCodec.TREND_SIGNAL_RULES.contains(item.insight().ruleVersion())
                        && item.insight().trendSignal() != null && !item.insight().trendSignal().isBlank()) {
                    trendSignal = item.insight().trendSignal().strip();
                }
            }
        }

        private boolean isCandidate() {
            return category != null && definition != null && !definition.isBlank();
        }

        private CandidateRepo toCandidate() {
            return new CandidateRepo(repositoryId, fullName, repoUrl, inAnchorDay, category,
                    appearedDates.size(), latestRank, latestStarsAdded, definition.strip(), trendSignal);
        }
    }

    /**
     * 筛选进入模型上下文的单个候选仓库摘要（不含 README 原文）。
     * @param repositoryId GitHub 稳定编号，未核实时为 null
     * @param fullName 窗口内最近一期记录的规范仓库全名（owner/repo）
     * @param repoUrl GitHub HTTPS 地址
     * @param inAnchorDay 是否收录于锚点当日榜单（前端决定站内详情或外链 GitHub）
     * @param category 8 大类代号
     * @param appearanceDays 7 日窗口累计上榜天数
     * @param latestRank 最近一次上榜排名
     * @param latestStarsAdded 最近一期有效新增星标数，缺失为 null
     * @param definition 一句话导语
     * @param trendSignal radar-3/radar-4 技术风向观察，旧版本可为 null
     */
    public record CandidateRepo(String repositoryId, String fullName, String repoUrl, boolean inAnchorDay,
                                String category, int appearanceDays, int latestRank, Long latestStarsAdded,
                                String definition, String trendSignal) {
        /** 判断该候选仓库是否具备 radar-3 及以上的有效技术风向观察。 */
        public boolean hasTrendSignal() {
            return trendSignal != null && !trendSignal.isBlank();
        }
    }

    /**
     * 冻结的简报输入快照与门槛评估结果。
     * @param stats 复用 2A 计算得到的 7 日确定性统计
     * @param candidates 按优先级截断后的有界候选仓库列表（最多 45 条）
     * @param allowedReposByLowerName 小写仓库名到规范 fullName 的只读白名单映射
     * @param candidatesByLowerName 小写仓库名到候选摘要的只读映射，供服务层补齐前端跳转元数据
     * @param materialJson 发送给模型的确定性 JSON 用户材料（严格受字节预算控制）
     * @param sourceFingerprint 64 位小写 SHA-256 输入快照指纹
     * @param eligible 是否同时满足 coveredDays >= 3 且 radar3ReadyCount >= 5（且候选池 >= 2）
     * @param ineligibleReason 未达门槛时的固定诊断码，达标时为 null
     */
    public record BriefingInput(TrendWindowStatsVO stats, List<CandidateRepo> candidates,
                                Map<String, String> allowedReposByLowerName,
                                Map<String, CandidateRepo> candidatesByLowerName,
                                String materialJson, String sourceFingerprint,
                                boolean eligible, String ineligibleReason) {
        /** 冻结集合，防止后续环节改写白名单或候选列表。 */
        public BriefingInput {
            candidates = List.copyOf(candidates);
            allowedReposByLowerName = Collections.unmodifiableMap(new LinkedHashMap<>(allowedReposByLowerName));
            candidatesByLowerName = Collections.unmodifiableMap(new LinkedHashMap<>(candidatesByLowerName));
        }

        /** 在发起付费生成前强制校验硬样本门槛，不足时直接抛出业务冲突异常，绝不为凑数调用模型。 */
        public void requireEligible() {
            if (!eligible) {
                if ("INSUFFICIENT_COVERED_DAYS".equals(ineligibleReason)) {
                    throw new BusinessException(ErrorCode.CONFLICT,
                            "当前 7 日窗口仅覆盖 " + stats.coveredDays() + " 天，至少需要 " + MIN_COVERED_DAYS + " 天已发布日报才可生成风口简报");
                }
                if ("INSUFFICIENT_RADAR3_READY".equals(ineligibleReason)) {
                    throw new BusinessException(ErrorCode.CONFLICT,
                            "当前窗口仅包含 " + stats.windowSummary().radar3ReadyCount() + " 个含风向观察的解读仓库，至少需要 "
                                    + MIN_RADAR3_READY_COUNT + " 个才可生成风口简报");
                }
                throw new BusinessException(ErrorCode.CONFLICT, "当前窗口候选样本不足，暂不可生成风口简报");
            }
        }
    }
}
