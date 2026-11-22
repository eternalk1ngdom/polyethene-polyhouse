package com.polyethene.polyhouse.model.vo;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 7 日榜内风向确定性统计结果，仅基于已发布日报快照计算，零模型费用且逐项可手算复核。
 * @param periodType 固定 DAILY，表明基于日榜归档聚合而非官方周榜
 * @param anchorDate 观察窗口锚点日期（北京时间）
 * @param windowStart 窗口起始自然日（含，anchorDate 往前 6 天）
 * @param windowEnd 窗口结束自然日（含，即 anchorDate）
 * @param windowDays 窗口自然日跨度，固定 7
 * @param coveredDays 窗口内实际有已发布日报的天数
 * @param coveredDates 窗口内实际有已发布日报的日期，按降序排列
 * @param missingDates 窗口内缺采或未发布的自然日，按降序排列，绝不猜造补齐
 * @param sampleStatus 样本充分度：INSUFFICIENT（<3 天）、LIMITED（3～4 天）、SUFFICIENT（>=5 天）
 * @param anchorSummary 锚点当日的分母与覆盖情况
 * @param windowSummary 7 日窗口去重仓库池的分母、旧稿归类与版本覆盖情况
 * @param categories 8 大类内有项目上榜的分类分布、占比变化与涨星中位数
 * @param recurringRepos 窗口内至少上榜 2 天的持续关注仓库列表（最多 15 条）
 */
public record TrendWindowStatsVO(String periodType, LocalDate anchorDate, LocalDate windowStart, LocalDate windowEnd,
                                 int windowDays, int coveredDays, List<LocalDate> coveredDates,
                                 List<LocalDate> missingDates, String sampleStatus,
                                 AnchorSummary anchorSummary, WindowSummary windowSummary,
                                 List<CategoryStat> categories, List<RecurringRepo> recurringRepos) {
    /** 冻结所有集合字段，防止返回后被外部修改导致统计视图不一致。 */
    public TrendWindowStatsVO {
        coveredDates = List.copyOf(coveredDates);
        missingDates = List.copyOf(missingDates);
        categories = List.copyOf(categories);
        recurringRepos = List.copyOf(recurringRepos);
    }

    /**
     * 锚点当日榜单分母摘要，便于核对当日分类占比分母与缺文来源。
     * @param totalItems 锚点当日收录项目总数
     * @param classifiedItems 锚点当日具备合法分类的项目数（当日分类占比分母）
     * @param unclassifiedItems 锚点当日无分类项目数
     * @param docListItems 锚点当日确认为资料清单（DOC_LIST）的项目数
     * @param missingInsightItems 锚点当日非资料清单但缺少解读的项目数
     */
    public record AnchorSummary(int totalItems, int classifiedItems, int unclassifiedItems,
                                int docListItems, int missingInsightItems) { }

    /**
     * 7 日去重仓库池的分母与资料质量摘要。
     * @param uniqueRepoCount 7 日窗口去重后独立仓库总数
     * @param totalAppearances 7 日窗口累计上榜人次
     * @param classifiedRepoCount 具备合法分类的独立仓库数（7 日分类占比分母）
     * @param unclassifiedRepoCount 无合法分类的独立仓库数
     * @param docListRepoCount 无分类中属于资料清单（DOC_LIST）的独立仓库数
     * @param missingInsightRepoCount 无分类中非资料清单但缺解读的独立仓库数
     * @param starsKnownRepoCount 窗口内具备明确新增星标数据的独立仓库数（缺失不计零）
     * @param staleClassifiedRepoCount 分类依据来自 STALE 旧解读的独立仓库数
     * @param oldestStaleAnalyzedAt 旧解读归类中最老的原稿生成时间（UTC），无旧稿归类时为 null
     * @param unverifiedIdentityCount 窗口内缺失稳定 repositoryId、退化按仓库名去重的独立仓库数
     * @param radar3ReadyCount 窗口内含 radar-3 且具备非空 trendSignal 的独立仓库数，供后续风口简报门槛参考
     */
    public record WindowSummary(int uniqueRepoCount, int totalAppearances, int classifiedRepoCount,
                                int unclassifiedRepoCount, int docListRepoCount, int missingInsightRepoCount,
                                int starsKnownRepoCount, int staleClassifiedRepoCount, Instant oldestStaleAnalyzedAt,
                                int unverifiedIdentityCount, int radar3ReadyCount) { }

    /**
     * 单个技术分类在 7 日窗口与锚点当日的聚合统计。
     * @param category 固定 8 大类枚举代号
     * @param anchorDayCount 锚点当日该类上榜仓库数
     * @param anchorDayShare 锚点当日占当日已分类数的比例（0～1，四位小数；分母为 0 时为 null）
     * @param windowUniqueCount 7 日窗口内该类去重独立仓库数
     * @param windowAppearances 7 日窗口内该类累计上榜人次
     * @param windowShare 7 日去重占窗口已分类仓库数的比例（0～1，四位小数；分母为 0 时为 null）
     * @param shareDelta 锚点日占比减去窗口内前序已采日期去重占比（四位小数；对比期不足或分母为 0 时为 null）
     * @param starsSampleCount 该类具备非空新增星标的去重仓库数（中位数分母）
     * @param medianStarsAdded 该类去重仓库最新期有效新增星标的中位数（缺失不计零；无样本为 null）
     * @param maxRepoStarsAdded 该类去重仓库最新期有效新增星标的最大值（便于识别单明星项目影响；无样本为 null）
     * @param staleClassifiedCount 该类依赖 STALE 旧解读分类的去重仓库数
     * @param oldestStaleAnalyzedAt 该类旧解读归类中最老的原稿时间（UTC），无则为 null
     * @param sampleRepos 该类按上榜天数与排名排序的代表仓库列表（最多 50 个），前端默认展示前 3 个并支持展开全部
     */
    public record CategoryStat(String category, int anchorDayCount, Double anchorDayShare,
                               int windowUniqueCount, int windowAppearances, Double windowShare,
                               Double shareDelta, int starsSampleCount, Double medianStarsAdded,
                               Long maxRepoStarsAdded, int staleClassifiedCount, Instant oldestStaleAnalyzedAt,
                               List<SampleRepo> sampleRepos) {
        /** 冻结代表仓库列表。 */
        public CategoryStat {
            sampleRepos = List.copyOf(sampleRepos);
        }
    }

    /**
     * 分类下的代表仓库摘要，支持前端区分跳转当期站内详情或外链 GitHub。
     * @param repositoryId GitHub 稳定数字编号，身份未核实时为 null
     * @param identityVerified 是否在窗口内取得过稳定仓库 ID
     * @param fullName 窗口内最近一期记录的仓库全名
     * @param repoUrl 对应 GitHub HTTPS 地址
     * @param inAnchorDay 是否同时收录于锚点当日榜单
     * @param appearanceDays 窗口内累计上榜天数
     * @param latestRank 窗口内最近一次上榜名次
     * @param latestStarsAdded 窗口内最近一期明确的新增星标数，未知为 null
     */
    public record SampleRepo(String repositoryId, boolean identityVerified, String fullName, String repoUrl,
                             boolean inAnchorDay, int appearanceDays, int latestRank, Long latestStarsAdded) { }

    /**
     * 窗口内多日重复上榜的仓库观察条目。
     * @param repositoryId GitHub 稳定数字编号，身份未核实时为 null
     * @param identityVerified 是否在窗口内取得过稳定仓库 ID
     * @param fullName 窗口内最近一期记录的仓库全名
     * @param repoUrl 对应 GitHub HTTPS 地址
     * @param inAnchorDay 是否同时收录于锚点当日榜单
     * @param category 最近一期有效解读的分类，无有效分类时为 null
     * @param definition 最近一期有效解读的一句话导语，无解读时为 null
     * @param appearanceDays 窗口内累计上榜天数（>= 2）
     * @param consecutiveDays 窗口内最长连续自然日上榜天数（缺采日会断开连续）
     * @param anchorStreakDays 截至锚点日的连续自然日上榜天数（锚点日未上榜为 0）
     * @param latestRank 窗口内最近一次上榜名次
     * @param bestRank 窗口内最高名次
     * @param latestStarsAdded 窗口内最近一期明确的新增星标数，未知为 null
     * @param medianStarsAdded 该仓库窗口内各期有效新增星标的中位数，全未知为 null
     * @param appearedDates 上榜日期列表（北京时间，按降序排列）
     * @param insightStatus 最近一次有效解读或最近一期快照的解读状态
     * @param insightAnalyzedAt 对应解读的原稿生成时间（UTC），无解读时为 null
     */
    public record RecurringRepo(String repositoryId, boolean identityVerified, String fullName, String repoUrl,
                                boolean inAnchorDay, String category, String definition,
                                int appearanceDays, int consecutiveDays, int anchorStreakDays,
                                int latestRank, int bestRank, Long latestStarsAdded, Double medianStarsAdded,
                                List<LocalDate> appearedDates, String insightStatus, Instant insightAnalyzedAt) {
        /** 冻结上榜日期列表。 */
        public RecurringRepo {
            appearedDates = List.copyOf(appearedDates);
        }
    }
}
