package com.polyethene.polyhouse.model.vo;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 7 日风口简报公开只读视图；当期无有效简报时返回 status=ABSENT（HTTP 200），避免读者端 404 噪音。
 * @param status READY（已有有效简报）或 ABSENT（当期未生成有效简报）
 * @param anchorDate 观察窗口锚点日期（北京时间）
 * @param windowStart 覆盖窗口起始自然日
 * @param windowEnd 覆盖窗口结束自然日（等于 anchorDate）
 * @param coveredDays 简报生成时覆盖的日报天数（ABSENT 时为当前窗口覆盖天数）
 * @param sampleStatus 简报样本档位：LIMITED（3～4 天）、SUFFICIENT（>=5 天）或 ABSENT 时的 INSUFFICIENT
 * @param currentCoveredDays 当前 7 日窗口实际已发布日报天数，供发布台核对门槛
 * @param radar3ReadyCount 当前 7 日窗口含 radar-3/radar-4 风向观察的独立仓库数，供发布台核对门槛
 * @param eligible 当前窗口是否同时满足 >=3 天且 >=5 个含风向观察仓库的硬门槛
 * @param ineligibleReason 未达硬门槛时的固定诊断码，达标时为 null
 * @param stale 若为 READY 且当期日报快照指纹或模型规则已变化则为 true，前端柔和提示“基于较早日报快照生成”
 * @param generatedAt 有效简报生成时间（UTC），ABSENT 时为 null
 * @param model 生成该简报的模型标识，ABSENT 时为 null
 * @param ruleVersion 简报规则版本（briefing-1），ABSENT 时为 null
 * @param headline 一句话总览，ABSENT 时为 null
 * @param overview 客观导语，ABSENT 时为 null
 * @param themes 2～3 条跨项目风口主题（含可点击的证据仓库跳转元数据），ABSENT 时为空列表
 * @param watchouts 1～2 条冷静边界提醒，ABSENT 时为空列表
 */
public record TrendBriefingVO(String status, LocalDate anchorDate, LocalDate windowStart, LocalDate windowEnd,
                              int coveredDays, String sampleStatus, int currentCoveredDays, int radar3ReadyCount,
                              boolean eligible, String ineligibleReason, boolean stale, Instant generatedAt,
                              String model, String ruleVersion, String headline, String overview,
                              List<ThemeView> themes, List<String> watchouts) {
    /** 冻结集合字段，防止序列化前在内存中被修改。 */
    public TrendBriefingVO {
        themes = List.copyOf(themes);
        watchouts = List.copyOf(watchouts);
    }

    /**
     * 单条跨项目风口主题视图。
     * @param title 主题短标题
     * @param signalType 信号类型：CONCENTRATION、RECURRING 或 EMERGING
     * @param summary 跨项目综合说明
     * @param evidenceRepos 已补齐站内/外链跳转信息的实名证据仓库列表
     */
    public record ThemeView(String title, String signalType, String summary, List<EvidenceRepo> evidenceRepos) {
        /** 冻结证据仓库列表。 */
        public ThemeView {
            evidenceRepos = List.copyOf(evidenceRepos);
        }
    }

    /**
     * 实名证据仓库跳转信息，读者可一键核验站内详情或 GitHub 原仓。
     * @param fullName 规范化后的仓库全名（owner/repo）
     * @param repositoryId GitHub 稳定数字编号，当期窗口无此元数据或旧稿仓库已出榜时为 null
     * @param repoUrl GitHub HTTPS 地址
     * @param inAnchorDay 是否收录于锚点当日榜单（true 时前端优先跳站内详情页）
     */
    public record EvidenceRepo(String fullName, String repositoryId, String repoUrl, boolean inAnchorDay) { }
}
