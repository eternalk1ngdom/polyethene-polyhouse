package com.polyethene.polyhouse.model.vo;

import java.time.Instant;
import java.util.List;

/**
 * 来源、模型和生成时间随公开版本冻结，不包含模型推理过程和原始 README。
 * @param category 有限业务分类，不视为人工审核
 * @param definition 一句话导语，独立说明谁用它做什么、得到什么
 * @param painPoint 材料支持的具体使用场景与交付结果，允许分段
 * @param comparison 有依据的同类差异，无依据可空
 * @param highlights 与正文互补的技术机制与设计亮点，不代表本站实测
 * @param documentedLimitations 项目材料明确说明的限制
 * @param inferredRisks 单独标识的模型推断风险，不冒充社区口碑
 * @param trendSignal 单独标识的 AI 技术风向观察，无依据或旧版本归档可空
 * @param analyzedAt 本次生成时间，复用时不刷新
 * @param readmeVersion 分析所依据的 GitHub SHA
 * @param model 实际模型标识，方便解释资料来源
 * @param ruleVersion 解读规则版本，规则变化后不默默复用旧规则
 */
public record TrendInsightVO(String category, String definition, String painPoint, String comparison,
                              List<String> highlights, List<String> documentedLimitations, List<String> inferredRisks,
                              String trendSignal,
                              Instant analyzedAt, String readmeVersion, String model, String ruleVersion) {
    /** 冻结列表，防止发布后在内存中被另一次分析改写。 */
    public TrendInsightVO {
        highlights = List.copyOf(highlights);
        documentedLimitations = List.copyOf(documentedLimitations);
        inferredRisks = List.copyOf(inferredRisks);
    }

    /** 兼容不包含技术风向字段的历史构造调用。 */
    public TrendInsightVO(String category, String definition, String painPoint, String comparison,
                          List<String> highlights, List<String> documentedLimitations, List<String> inferredRisks,
                          Instant analyzedAt, String readmeVersion, String model, String ruleVersion) {
        this(category, definition, painPoint, comparison, highlights, documentedLimitations, inferredRisks,
                null, analyzedAt, readmeVersion, model, ruleVersion);
    }
}
