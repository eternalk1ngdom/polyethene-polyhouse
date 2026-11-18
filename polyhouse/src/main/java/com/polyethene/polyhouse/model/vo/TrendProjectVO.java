package com.polyethene.polyhouse.model.vo;

/**
 * 当期基础资料快照；不包含 README 正文、上游错误或内部令牌。
 * @param rankNum 来源榜单排名，筛选后也不重排为新的官方排名
 * @param repositoryId GitHub 编号以字符串传输；仓库身份无法确认时为 null
 * @param fullName 当时的仓库全名
 * @param repoUrl 由已校验全名生成的 GitHub HTTPS 链接
 * @param description 当时的公开项目描述
 * @param primaryLanguage 主语言，未知为 null
 * @param totalStars 采集到的总 Star，未知为 null 而非零
 * @param starsAdded 来源标注的本周期新增 Star，未知为 null
 * @param forksCount Fork 数，未知为 null
 * @param license 识别的 SPDX 许可证，未识别为 null，不表示允许商用
 * @param archived GitHub 是否已归档，资料未知为 null
 * @param repoType CODE_PROJECT、DOC_LIST 或 UNKNOWN，不以无语言直接判文档
 * @param classificationReason 分类依据的稳定代号，避免把猜测说成事实
 * @param metadataStatus AVAILABLE 或 UNAVAILABLE，说明元数据是否完整补全
 * @param readmeStatus AVAILABLE、MISSING 或 UNAVAILABLE，区分不存在与获取失败
 * @param insightStatus 区分 ANALYZED、REUSED、STALE 及缺失原因，不伪造 AI 已完成
 * @param insight 当前选用的解读，缺失为 null；旧日报未有此字段也可读取
 */
public record TrendProjectVO(int rankNum, String repositoryId, String fullName, String repoUrl,
                             String description, String primaryLanguage, Long totalStars, Long starsAdded,
                             Long forksCount, String license, Boolean archived, String repoType,
                             String classificationReason, String metadataStatus, String readmeStatus,
                             String insightStatus, TrendInsightVO insight) {
    /** 基础采集仍不依赖 AI，兼容既有内部投影及历史 JSON 缺失解读字段。 */
    public TrendProjectVO(int rankNum, String repositoryId, String fullName, String repoUrl,
                           String description, String primaryLanguage, Long totalStars, Long starsAdded,
                           Long forksCount, String license, Boolean archived, String repoType,
                           String classificationReason, String metadataStatus, String readmeStatus, String insightStatus) {
        this(rankNum, repositoryId, fullName, repoUrl, description, primaryLanguage, totalStars, starsAdded,
                forksCount, license, archived, repoType, classificationReason, metadataStatus, readmeStatus, insightStatus, null);
    }

    /** 更新仓库标识时保留榜单资料及完整度，避免把身份线索当成已补齐资料。 */
    public TrendProjectVO withRepositoryId(String id) {
        return new TrendProjectVO(rankNum, id, fullName, repoUrl, description, primaryLanguage,
                totalStars, starsAdded, forksCount, license, archived, repoType, classificationReason,
                metadataStatus, readmeStatus, insightStatus, insight);
    }

    /** 只替换解读投影，保留本次榜单的基础资料，不回写其他日报。 */
    public TrendProjectVO withInsight(String status, TrendInsightVO value) {
        return new TrendProjectVO(rankNum, repositoryId, fullName, repoUrl, description, primaryLanguage,
                totalStars, starsAdded, forksCount, license, archived, repoType, classificationReason,
                metadataStatus, readmeStatus, status, value);
    }
}
