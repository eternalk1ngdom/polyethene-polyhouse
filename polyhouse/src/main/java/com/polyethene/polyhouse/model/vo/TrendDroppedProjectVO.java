package com.polyethene.polyhouse.model.vo;

/**
 * 上一已发布日在榜、当期退榜的项目速览，仅用于榜单底部默认折叠区。
 * @param previousRank 上一已发布日来源排名
 * @param repositoryId GitHub 稳定仓库 ID，未知为 null
 * @param fullName 仓库全名（owner/repo）
 * @param repoUrl 已校验的 GitHub HTTPS 链接
 * @param primaryLanguage 主语言，未知为 null
 * @param category 技术分类代号，未解读或未知为 null
 * @param previousStarsAdded 上一已发布日新增星标，未知为 null
 */
public record TrendDroppedProjectVO(
        int previousRank,
        String repositoryId,
        String fullName,
        String repoUrl,
        String primaryLanguage,
        String category,
        Long previousStarsAdded) {
}
