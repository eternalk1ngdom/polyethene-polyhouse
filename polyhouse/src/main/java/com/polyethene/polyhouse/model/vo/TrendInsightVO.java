package com.polyethene.polyhouse.model.vo;

import java.time.Instant;
import java.util.List;

/**
 * 来源、模型和生成时间随公开版本冻结，不包含模型推理过程和原始 README。
 * @param category 有限业务分类，不视为人工审核
 * @param definition 一句话定义
 * @param painPoint 材料支持的用途和问题
 * @param comparison 有依据的同类差异，无依据可空
 * @param highlights 材料支持的技术特点，不代表本站实测
 * @param documentedLimitations 项目材料明确说明的限制
 * @param inferredRisks 单独标识的模型推断，不冒充社区口碑
 * @param analyzedAt 本次生成时间，复用时不刷新
 * @param readmeVersion 分析所依据的 GitHub SHA
 * @param model 实际模型标识，方便解释资料来源
 * @param ruleVersion 解读规则版本，规则变化后不默默复用旧规则
 */
public record TrendInsightVO(String category, String definition, String painPoint, String comparison,
                              List<String> highlights, List<String> documentedLimitations, List<String> inferredRisks,
                              Instant analyzedAt, String readmeVersion, String model, String ruleVersion) {
    /** 冻结列表，防止发布后在内存中被另一次分析改写。 */
    public TrendInsightVO {
        highlights = List.copyOf(highlights);
        documentedLimitations = List.copyOf(documentedLimitations);
        inferredRisks = List.copyOf(inferredRisks);
    }
}
