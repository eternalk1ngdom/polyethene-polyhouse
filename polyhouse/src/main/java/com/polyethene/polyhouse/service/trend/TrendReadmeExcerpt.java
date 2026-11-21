package com.polyethene.polyhouse.service.trend;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeVisitor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.regex.Pattern;

/** 在一次任务的输入预算内，保留能解释用途、实现和适用边界的原文。 */
final class TrendReadmeExcerpt {
    static final int MAX_CHARS = 5000;
    private enum Topic { INTRO, USAGE, DESIGN, LIMIT, OTHER, SETUP, NAV }
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern UNDERLINED = Pattern.compile("(?m)^([^\\r\\n]{1,160})\\R(={3,}|-{3,})[ \\t]*\\r?$");
    private static final Pattern USAGE = Pattern.compile("(?i)\\b(usage|use cases?|examples?|inputs?|outputs?|workflow|quick ?start)\\b|用法|使用|示例|场景|输入|输出|工作流");
    private static final Pattern DESIGN = Pattern.compile("(?i)\\b(architecture|design|features?|implementation|how it works)\\b|架构|原理|实现|机制|设计|特性");
    private static final Pattern LIMIT = Pattern.compile("(?i)\\b(limitations?|requirements?|compatibility|constraints?|caveats?|supported platforms?)\\b|限制|要求|兼容|适用|依赖");
    private static final Pattern SETUP = Pattern.compile("(?i)\\b(install(ation)?|setup|building from source)\\b|安装|构建");
    private static final Pattern NAV = Pattern.compile("(?i)^(table of contents|contents|badges|sponsors?|目录|导航|徽章|赞助)\\s*#*$");
    private static final Pattern IMAGE = Pattern.compile("!?\\[!\\[[^\\]\\n]{0,200}\\]\\([^()\\n]{0,1000}\\)\\]\\([^()\\n]{0,1000}\\)|!\\[[^\\]\\n]{0,200}\\]\\([^()\\n]{0,1000}\\)");
    private static final Pattern ANCHOR = Pattern.compile("^(?:[-*+]\\s*)?\\[[^\\]\\n]{1,200}\\]\\(#[^()\\n]{1,300}\\)$");
    private static final Pattern INSTALL_COMMAND = Pattern.compile("^(?:\\$\\s+)?(?:npm (?:i|install)|pip3? install|pnpm (?:i|install)|yarn install|git clone)(?:\\s|$).*");

    private TrendReadmeExcerpt() { }

    static String select(String source) {
        var sections = new ArrayList<Section>();
        var current = new Section(Topic.INTRO, "");
        sections.add(current);
        var parents = new Topic[7];
        Arrays.fill(parents, Topic.OTHER);
        var titles = new String[7];
        Arrays.fill(titles, "");
        String fence = "";
        int codeLines = 0;
        var lines = plainText(source).lines().iterator();
        while (lines.hasNext()) {
            String line = lines.next().strip();
            if (!fence.isEmpty()) {
                if (line.startsWith(fence) && line.chars().allMatch(c -> c == line.charAt(0))) fence = "";
                else if ((current.topic == Topic.USAGE || current.topic == Topic.DESIGN) && !line.isEmpty()
                        && !INSTALL_COMMAND.matcher(line).matches() && codeLines++ < 4) current.append(line);
                continue;
            }
            if (line.startsWith("```") || line.startsWith("~~~")) {
                int end = 1;
                while (end < line.length() && line.charAt(end) == line.charAt(0)) end++;
                fence = line.substring(0, end);
                codeLines = 0;
                continue;
            }
            var heading = HEADING.matcher(line);
            if (line.length() <= 200 && heading.matches()) {
                int level = heading.group(1).length();
                String title = heading.group(2).strip();
                Topic topic = classify(title, level == 1 ? Topic.INTRO : parents[level - 1]);
                Arrays.fill(parents, level, parents.length, topic);
                // 单取“小节：Windows”会丢失“已知限制”等父标题，保留小节所属的语境。
                String context = level > 2 && !titles[level - 1].isEmpty() ? titles[level - 1] + " / " + title : title;
                Arrays.fill(titles, level, titles.length, context);
                current = new Section(topic, context);
                sections.add(current);
            } else if (!line.isBlank() && current.topic != Topic.NAV && !ANCHOR.matcher(line).matches()
                    && !INSTALL_COMMAND.matcher(line).matches()) {
                current.append(line);
            }
        }

        // 先让每类首个有正文的章节占到位置，再依原序补足；安装说明仅用剩余额度。
        var reserved = EnumSet.of(Topic.INTRO, Topic.USAGE, Topic.DESIGN, Topic.LIMIT);
        int remaining = MAX_CHARS;
        for (var section : sections) {
            if (!section.body.isEmpty() && reserved.remove(section.topic)) remaining -= section.take(1050);
        }
        for (boolean setup : List.of(false, true)) {
            for (var section : sections) {
                if ((section.topic == Topic.SETUP) == setup) remaining -= section.take(remaining);
            }
        }
        var result = new StringBuilder();
        for (var section : sections) section.write(result);
        return result.toString().strip();
    }

    private static Topic classify(String title, Topic parent) {
        if (LIMIT.matcher(title).find()) return Topic.LIMIT;
        if (NAV.matcher(title).matches()) return Topic.NAV;
        if (DESIGN.matcher(title).find()) return Topic.DESIGN;
        if (USAGE.matcher(title).find()) return Topic.USAGE;
        if (SETUP.matcher(title).find()) return Topic.SETUP;
        if (title.matches("(?i)overview|about|introduction|简介|概述|介绍")) return Topic.INTRO;
        return parent;
    }

    private static String plainText(String source) {
        var body = Jsoup.parseBodyFragment(source).body();
        body.select("script,style,svg,img,nav").remove();
        var out = new StringBuilder();
        // Markdown 需要原始换行；HTML 块另加换行，行内标签保留连续正文。
        body.traverse(new NodeVisitor() {
            @Override public void head(Node node, int depth) {
                if (node instanceof TextNode text) out.append(text.getWholeText());
                if (node instanceof Element tag) {
                    String name = tag.normalName();
                    if (tag.isBlock() || name.equals("br")) out.append('\n');
                    if (name.equals("pre")) out.append("```\n");
                    if (name.length() == 2 && name.charAt(0) == 'h' && name.charAt(1) >= '1' && name.charAt(1) <= '6') {
                        out.append("#".repeat(name.charAt(1) - '0')).append(' ');
                    }
                }
            }
            @Override public void tail(Node node, int depth) {
                if (node instanceof Element tag && tag.isBlock()) {
                    if (tag.normalName().equals("pre")) out.append("\n```");
                    out.append('\n');
                }
            }
        });
        String text = IMAGE.matcher(out).replaceAll("");
        return UNDERLINED.matcher(text).replaceAll(match -> java.util.regex.Matcher.quoteReplacement(
                (match.group(2).startsWith("=") ? "# " : "## ") + match.group(1)));
    }

    private static final class Section {
        final Topic topic;
        final String title;
        final StringBuilder body = new StringBuilder();
        int used;

        Section(Topic topic, String title) {
            this.topic = topic;
            this.title = title;
        }
        void append(String line) {
            if (body.isEmpty() && !title.isEmpty()) body.append(title).append('\n');
            if (!body.isEmpty() && body.charAt(body.length() - 1) != '\n') body.append('\n');
            body.append(line);
        }
        int take(int budget) {
            // 每段连同一个分隔符计入额度，避免多段相加超过模型请求限制。
            if (budget < 4 || body.isEmpty()) return 0;
            int extra = Math.min(budget, body.length() + 1 - used);
            used += extra;
            return extra;
        }
        void write(StringBuilder result) {
            if (used == 0) return;
            if (used == body.length() + 1) result.append(body);
            else {
                int end = used - 2;
                if (end > 0 && Character.isHighSurrogate(body.charAt(end - 1))) end--;
                result.append(body, 0, end).append('…');
            }
            result.append('\n');
        }
    }
}
