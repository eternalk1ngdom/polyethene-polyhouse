package com.polyethene.polyhouse.service.trend;

import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.model.vo.TrendProjectVO;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** 只解析日榜结构，不运行脚本；宁可拒绝异常页面，也不发布空榜或重新编号的残缺榜。 */
@Component
public class GithubTrendingParser {
    /** GitHub owner/repo 的受限路径字符，禁止借仓库链接注入绝对 URL 或转义片段。 */
    public static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9-]{1,39}/(?!\\.{1,2}$)[A-Za-z0-9_.-]{1,100}");
    /** 固定来源，公开查询和采集都不接受客户端 URL。 */
    public static final String SOURCE = "https://github.com/trending?since=daily";

    /** 只取来源前二十五项，关键行结构错误或全部指标消失则判整体解析失效。 */
    public List<TrendProjectVO> parse(String html) {
        if (html == null || html.length() > 1024 * 1024) throw invalidPage();
        var document = Jsoup.parse(html);
        var rows = document.select("article.Box-row");
        var title = document.selectFirst("h1");
        if (rows.isEmpty() || rows.size() > 100 || title == null || !title.text().equals("Trending")) throw invalidPage();
        List<TrendProjectVO> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Element row : rows.stream().limit(25).toList()) {
            var heading = row.selectFirst("h2 a[href]");
            String name = heading == null ? "" : heading.attr("href").replaceFirst("^/", "");
            if (!REPOSITORY.matcher(name).matches() || !seen.add(name.toLowerCase(Locale.ROOT))) throw invalidPage();
            var growth = row.selectFirst("span.float-sm-right");
            String added = growth == null ? "" : growth.text();
            Long starsAdded = added.matches("[0-9,.kKmM]+ stars? today")
                    ? number(added.substring(0, added.indexOf(' '))) : null;
            // 依据仓库全名精确匹配指标链接，不把赞助或开发者链接当作 Star。
            items.add(new TrendProjectVO(items.size() + 1, null, name, "https://github.com/" + name,
                    text(row.selectFirst("p"), 2000), text(row.selectFirst("[itemprop=programmingLanguage]"), 100),
                    number(text(row.selectFirst("a[href='/" + name + "/stargazers']"), 32)),
                    starsAdded, number(text(row.selectFirst("a[href='/" + name + "/forks']"), 32)),
                    null, null, "UNKNOWN", "INSUFFICIENT_EVIDENCE", "UNAVAILABLE", "UNAVAILABLE", "NOT_ANALYZED"));
        }
        if (items.stream().allMatch(item -> item.totalStars() == null && item.forksCount() == null)) throw invalidPage();
        return List.copyOf(items);
    }

    /** 展示文本只截断不解释为 HTML；来源缺失与空值一致为 null。 */
    public static String text(Element element, int limit) {
        return element == null || element.text().isBlank() ? null
                : element.text().substring(0, Math.min(limit, element.text().length()));
    }

    private static Long number(String raw) {
        if (raw == null) return null;
        String value = raw.strip().toLowerCase(Locale.ROOT);
        // 接受标准千位分隔与 k/m 紧凑显示，畸形分组不通过删除逗号伪装成合法数。
        if (!value.matches("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]+)?[km]?")) return null;
        BigDecimal multiplier = value.endsWith("k") ? BigDecimal.valueOf(1000)
                : value.endsWith("m") ? BigDecimal.valueOf(1000000) : BigDecimal.ONE;
        String numeric = value.replace(",", "").replaceFirst("[km]$", "");
        try {
            long result = new BigDecimal(numeric).multiply(multiplier).longValueExact();
            return result <= 9_007_199_254_740_991L ? result : null;
        } catch (ArithmeticException failure) {
            return null; // 非整数和超范围指标是未知，不伪造为零。
        }
    }

    private static BusinessException invalidPage() {
        return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "GitHub 日榜结构暂时无法识别");
    }
}
