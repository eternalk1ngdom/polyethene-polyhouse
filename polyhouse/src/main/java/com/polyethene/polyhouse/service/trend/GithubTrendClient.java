package com.polyethene.polyhouse.service.trend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.config.TrendProperties;
import com.polyethene.polyhouse.model.entity.TrendCollection;
import com.polyethene.polyhouse.model.vo.TrendProjectVO;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** 串行补资料，一旦上游不可用即停止本轮 API 补全，避免限流后继续轰击；有效基础榜单仍可归档。 */
@Component
public class GithubTrendClient {
    private static final Set<String> BUILDS = Set.of("pom.xml", "build.gradle", "build.gradle.kts", "package.json",
            "cargo.toml", "go.mod", "pyproject.toml", "setup.py", "cmakelists.txt", "makefile", "gemfile");
    private final TrendSourceTransport transport;
    private final GithubTrendingParser parser;
    private final ObjectMapper json;
    private final TrendProperties settings;

    /** 网络组件只接收固定来源，令牌只由服务端配置读取。 */
    public GithubTrendClient(TrendSourceTransport transport, GithubTrendingParser parser,
                             ObjectMapper json, TrendProperties settings) {
        this.transport = transport;
        this.parser = parser;
        this.json = json.copy();
        this.json.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(64).maxStringLength(512 * 1024).maxNumberLength(32).build());
        this.settings = settings;
    }

    /** 尚未启用时不访问外网；采集开始时间只由服务端产生，后续期号据此确定。 */
    public TrendCollection collect() {
        return collect(() -> { });
    }

    /** 持久任务在每个请求及重定向前检查执行权，撤销异常不作为单仓库失败吞掉。 */
    public TrendCollection collect(Runnable guard) {
        Objects.requireNonNull(guard);
        if (!settings.enabled()) throw unavailable();
        Instant captured = Instant.now();
        var reply = get(URI.create(GithubTrendingParser.SOURCE), guard);
        if (reply.status() != 200) throw unavailable();
        List<TrendCollection.Project> projects = new ArrayList<>();
        boolean apiPaused = false;
        for (var base : parser.parse(reply.body())) {
            guard.run();
            TrendProjectVO snapshot = base;
            String readmeVersion = null;
            String readmeText = "";
            if (!apiPaused) {
                try {
                    var metadata = object(get(URI.create("https://api.github.com/repos/" + base.fullName()), guard));
                    snapshot = metadata(base, metadata);
                    String prefix = "https://api.github.com/repos/" + snapshot.fullName();
                    var files = array(get(URI.create(prefix + "/contents"), guard));
                    var readme = get(URI.create(prefix + "/readme"), guard);
                    if (readme.status() == 404) {
                        snapshot = classify(snapshot, files, "MISSING");
                    } else {
                        var material = object(readme);
                        if (!"base64".equals(material.path("encoding").asText())
                                || !material.path("size").canConvertToInt() || material.path("size").asInt() < 0
                                || material.path("size").asInt() > 256 * 1024
                                || !material.path("sha").asText().matches("[a-f0-9]{40,64}")) throw unavailable();
                        // GitHub 的 base64 含换行；仅移除 CR/LF，不能默默接受任意无效字符。
                        byte[] bytes = Base64.getDecoder().decode(material.path("content").asText().replace("\n", "").replace("\r", ""));
                        if (bytes.length != material.path("size").asInt()) throw unavailable();
                        var document = Jsoup.parse(new String(bytes, StandardCharsets.UTF_8));
                        document.select("script,style,svg,img").remove();
                        String text = document.text().replaceAll("!\\[[^\\]\\r\\n]{0,200}\\]\\([^\\)\\r\\n]{0,1000}\\)", "");
                        readmeText = text.substring(0, Math.min(text.length(), 5000));
                        readmeVersion = material.path("sha").asText();
                        snapshot = classify(snapshot, files, "AVAILABLE");
                    }
                } catch (ApiPaused failure) {
                    // 限流或连接故障属于上游级失败，停止本轮后续 API，保留已得到的公开资料。
                    apiPaused = true;
                } catch (BusinessException | IllegalArgumentException failure) {
                    // 单仓库删除、材料过大或格式异常只影响该仓库，丢弃未完成材料后继续其他项目。
                    readmeVersion = null;
                    readmeText = "";
                }
            }
            projects.add(new TrendCollection.Project(snapshot, readmeVersion, readmeText));
        }
        return new TrendCollection(captured, projects);
    }

    private TrendSourceTransport.Reply get(URI initial, Runnable guard) {
        URI target = initial;
        for (int hop = 0; hop <= 2; hop++) {
            GithubTrendHttp.requireAllowed(target);
            guard.run();
            final TrendSourceTransport.Reply reply;
            try { reply = transport.get(target, target.getHost().equals("api.github.com") ? settings.githubToken() : null); }
            catch (BusinessException failure) { throw new ApiPaused(); }
            if (reply.status() == 403 || reply.status() == 429 || reply.status() >= 500) throw new ApiPaused();
            if (!Set.of(301, 302, 307, 308).contains(reply.status())) return reply;
            if (reply.location() == null || reply.location().length() > 512) throw unavailable();
            URI next = target.resolve(reply.location());
            // 同源安全跳转允许仓库转移；HTML 不能跳转到 API，也不能把 token 送到网页。
            if (!Objects.equals(next.getHost(), target.getHost())) throw unavailable();
            target = next;
        }
        throw unavailable();
    }

    private JsonNode object(TrendSourceTransport.Reply reply) {
        var node = decode(reply);
        if (node == null || !node.isObject()) throw unavailable();
        return node;
    }

    private JsonNode array(TrendSourceTransport.Reply reply) {
        var node = decode(reply);
        if (node == null || !node.isArray() || node.size() > 1000) throw unavailable();
        return node;
    }

    private JsonNode decode(TrendSourceTransport.Reply reply) {
        if (reply.status() != 200) throw unavailable();
        try { return json.readTree(reply.body()); }
        catch (java.io.IOException failure) { throw unavailable(); }
    }

    private TrendProjectVO metadata(TrendProjectVO base, JsonNode data) {
        String name = data.path("full_name").asText();
        if (!GithubTrendingParser.REPOSITORY.matcher(name).matches() || !data.path("id").isIntegralNumber() || !data.path("id").canConvertToLong()
                || data.path("id").asLong() <= 0 || !data.path("private").isBoolean()
                || data.path("private").asBoolean()) throw unavailable();
        String license = string(data.path("license"), "spdx_id", 100);
        if ("NOASSERTION".equals(license)) license = null;
        return new TrendProjectVO(base.rankNum(), data.path("id").asText(), name, "https://github.com/" + name,
                string(data, "description", 2000), string(data, "language", 100),
                base.totalStars() == null ? metric(data.path("stargazers_count")) : base.totalStars(), base.starsAdded(),
                base.forksCount() == null ? metric(data.path("forks_count")) : base.forksCount(), license,
                data.path("archived").isBoolean() ? data.path("archived").asBoolean() : null,
                "UNKNOWN", "INSUFFICIENT_EVIDENCE", "AVAILABLE", "UNAVAILABLE", "NOT_ANALYZED");
    }

    private TrendProjectVO classify(TrendProjectVO item, JsonNode files, String readmeStatus) {
        Set<String> names = new HashSet<>();
        Set<String> dirs = new HashSet<>();
        for (var file : files) {
            if ("file".equals(file.path("type").asText())) names.add(file.path("name").asText().toLowerCase(Locale.ROOT));
            if ("dir".equals(file.path("type").asText())) dirs.add(file.path("name").asText().toLowerCase(Locale.ROOT));
        }
        boolean build = names.stream().anyMatch(BUILDS::contains);
        boolean source = dirs.stream().anyMatch(Set.of("src", "lib", "app", "cmd", "crates", "packages")::contains);
        boolean namedResource = item.fullName().toLowerCase(Locale.ROOT)
                .matches(".*(?:awesome|interview|roadmap|tutorial|books).*" );
        boolean docsOnly = !files.isEmpty() && dirs.stream().allMatch(Set.of("docs", "images", "assets", ".github", "examples")::contains)
                && names.stream().allMatch(name -> name.endsWith(".md") || name.endsWith(".txt")
                || Set.of("license", "licence", ".gitignore", ".gitattributes").contains(name));
        String type = build && source ? "CODE_PROJECT" : namedResource && docsOnly ? "DOC_LIST" : "UNKNOWN";
        String reason = type.equals("CODE_PROJECT") ? "BUILD_AND_SOURCE" : type.equals("DOC_LIST")
                ? "RESOURCE_NAME_AND_DOC_FILES" : "INSUFFICIENT_EVIDENCE";
        return new TrendProjectVO(item.rankNum(), item.repositoryId(), item.fullName(), item.repoUrl(), item.description(),
                item.primaryLanguage(), item.totalStars(), item.starsAdded(), item.forksCount(), item.license(), item.archived(),
                type, reason, item.metadataStatus(), readmeStatus, type.equals("DOC_LIST") ? "NOT_REQUIRED" : "NOT_ANALYZED");
    }

    private static String string(JsonNode object, String field, int max) {
        var value = object.path(field);
        return !value.isTextual() || value.asText().isBlank() ? null : value.asText().substring(0, Math.min(max, value.asText().length()));
    }

    private static Long metric(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.asLong() >= 0
                && value.asLong() <= 9_007_199_254_740_991L ? value.asLong() : null;
    }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "GitHub 资料暂时不可用");
    }

    /** 仅标记应停止本轮 API 的失败，不将单项目的 404 当作整个来源不可用。 */
    private static final class ApiPaused extends BusinessException {
        private static final long serialVersionUID = 1L;
        private ApiPaused() { super(ErrorCode.SERVICE_UNAVAILABLE, "GitHub 资料暂时不可用"); }
    }
}
