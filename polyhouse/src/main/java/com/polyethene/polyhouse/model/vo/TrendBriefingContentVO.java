package com.polyethene.polyhouse.model.vo;

import java.util.List;

/**
 * 经服务端白名单与纯文本校验通过的 7 日风口简报正文结构；仅保存脱敏结论，不含原始 Prompt 或模型推理过程。
 * @param headline 一句话总览本周最显著的技术共性（12～40 字符）
 * @param overview 客观导语，交代核心聚焦点与样本覆盖边界（60～220 字符）
 * @param themes 2～3 条跨项目风口主题，每条必须绑定 2～5 个真实收录仓库
 * @param watchouts 1～2 条冷静边界提醒，提示缺采日、单明星项目拉动或日榜局限
 */
public record TrendBriefingContentVO(String headline, String overview, List<Theme> themes, List<String> watchouts) {
    /** 冻结集合字段，避免持久化或公开响应前在内存中被意外篡改。 */
    public TrendBriefingContentVO {
        themes = List.copyOf(themes);
        watchouts = List.copyOf(watchouts);
    }

    /**
     * 单条跨项目风口主题。
     * @param title 主题短标题（6～24 字符）
     * @param signalType 信号类型：CONCENTRATION（多仓聚集）、RECURRING（持续霸榜）或 EMERGING（新苗头）
     * @param summary 跨项目综合说明：共同解决什么问题、路径差异与待观察信号（80～240 字符）
     * @param evidenceRepos 经快照白名单核验并归一化大小写的实名证据仓库全名（2～5 个）
     */
    public record Theme(String title, String signalType, String summary, List<String> evidenceRepos) {
        /** 冻结证据仓库列表。 */
        public Theme {
            evidenceRepos = List.copyOf(evidenceRepos);
        }
    }
}
