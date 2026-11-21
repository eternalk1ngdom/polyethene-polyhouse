package com.polyethene.polyhouse.service.trend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.config.TrendProperties;
import com.polyethene.polyhouse.model.entity.TrendCollection;
import com.polyethene.polyhouse.model.vo.TrendProjectVO;
import com.polyethene.polyhouse.model.entity.TrendSourceIssue;
import com.polyethene.polyhouse.model.entity.TrendSourceIssue.Reason;
import com.polyethene.polyhouse.model.entity.TrendSourceIssue.Stage;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.*;

/** 串行补资料，一旦上游不可用即停止本轮 API 补全，避免限流后继续轰击；有效基础榜单仍可归档。 */
@Component
public class GithubTrendClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(GithubTrendClient.class);
    private static final Set<String> BUILDS = Set.of("pom.xml", "build.gradle", "build.gradle.kts", "package.json",
            "cargo.toml", "go.mod", "pyproject.toml", "setup.py", "cmakelists.txt", "makefile", "gemfile");
    private final TrendSourceTransport transport;
    private final GithubTrendingParser parser;
    private final ObjectMapper json;
    private final TrendProperties settings;

    private final Clock clock;
    // 每个固定来源只保存一个等待期限，后续任务不占线程等待。
    private final AtomicReference<Instant> apiRetryAt = new AtomicReference<>(Instant.EPOCH);
    private final AtomicReference<Instant> listingRetryAt = new AtomicReference<>(Instant.EPOCH);

    /** 网络组件只接收固定来源，令牌只由服务端配置读取。 */
    public GithubTrendClient(TrendSourceTransport transport, GithubTrendingParser parser,
                             ObjectMapper json, TrendProperties settings) {
        this(transport, parser, json, settings, Clock.systemUTC());
    }

    /** 用同一时钟记录采集时刻和等待期限，便于复核限流后的恢复。 */
    @Autowired
    public GithubTrendClient(TrendSourceTransport transport, GithubTrendingParser parser,
                             ObjectMapper json, TrendProperties settings, Clock clock) {
        this.clock = clock;
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
        return collect(guard, Set.of());
    }

    /** 缺稿提示只改变补全顺序；榜单身份和公开排名始终取自本次采集。 */
    public TrendCollection collect(Runnable guard, Set<String> missingInsightIds) {
        Objects.requireNonNull(guard);
        if (!settings.enabled()) throw unavailable();
        var batch = new Batch(clock.instant());
        List<TrendProjectVO> ranking;
        try {
            var page = get(URI.create(GithubTrendingParser.SOURCE), guard);
            try { ranking = parser.parse(page.body()); }
            catch (BusinessException failure) { throw invalid(); }
        } catch (FetchFailure failure) {
            batch.failed("daily", Stage.LISTING, failure);
            throw failure;
        }
        // 稳定排序保留同一优先级内的榜序；数组按真实排名落位，发布时无需重新编号。
        // 预留每一行自己的榜单名称，避免仓库改名后出现同名行，让整期归档失败。
        ranking.forEach(item -> batch.names.add(item.fullName().toLowerCase(Locale.ROOT)));
        var order = ranking.stream().sorted(Comparator.comparing(item ->
                item.repositoryId() == null || !missingInsightIds.contains(item.repositoryId()))).toList();
        var projects = new TrendCollection.Project[ranking.size()];
        for (var listed : order) {
            guard.run();
            projects[listed.rankNum() - 1] = collectProject(listed, batch, guard);
        }
        if (batch.firstIssue != null || batch.skipped > 0) {
            LOGGER.warn("星潮采集汇总：任务={}，采集时间={}，首个仓库={}，首个故障={}，故障数={}，整仓跳过数={}",
                    MDC.get("trendJob"), batch.captured, batch.firstRepo, batch.firstIssue, batch.failures, batch.skipped);
        }
        return new TrendCollection(batch.captured, List.of(projects));
    }

    private TrendCollection.Project collectProject(TrendProjectVO listed, Batch batch, Runnable guard) {
        String name = listed.fullName();
        if (batch.pause != null || clock.instant().isBefore(apiRetryAt.get())) {
            boolean cooling = batch.pause == null;
            var cause = new TrendSourceIssue(Stage.METADATA, cooling ? Reason.COOLDOWN : Reason.SKIPPED_AFTER_PAUSE,
                    null, cooling ? apiRetryAt.get() : batch.pause.retryAt());
            batch.skipped++;
            LOGGER.info("星潮跳过补全：采集时间={}，仓库={}，原因={}", batch.captured, name, cause);
            return new TrendCollection.Project(listed, null, "", cause);
        }
        TrendProjectVO snapshot;
        try {
            var reply = get(URI.create("https://api.github.com/repos/" + name), guard);
            snapshot = metadata(listed, object(reply));
            if (!snapshot.fullName().equalsIgnoreCase(name) && !batch.names.add(snapshot.fullName().toLowerCase(Locale.ROOT))) {
                throw new FetchFailure(Reason.IDENTITY_CONFLICT, 200, null);
            }
        } catch (FetchFailure failure) {
            var issue = batch.failed(name, Stage.METADATA, failure);
            // 来源级中断保留榜单的稳定 ID；明确不可见或冲突的仓库不能借旧稿恢复身份。
            var fallback = failure.pausesSource() ? listed : listed.withRepositoryId(null);
            return new TrendCollection.Project(fallback, null, "", issue);
        }

        var material = new Readme(null, "", "UNAVAILABLE");
        TrendSourceIssue issue = null;
        try {
            material = readme(getMaterial(snapshot, "/readme", guard));
        } catch (FetchFailure failure) {
            if (Objects.equals(failure.status, 404)) {
                failure = new FetchFailure(Reason.README_NOT_FOUND, 404, null);
                material = new Readme(null, "", "MISSING");
            }
            issue = batch.failed(name, Stage.README, failure);
            if (issue.reason() == Reason.UNVERIFIED_REDIRECT) return new TrendCollection.Project(listed.withRepositoryId(null), null, "", issue);
        }
        JsonNode files = json.createArrayNode();
        if (batch.pause == null) {
            try {
                files = array(getMaterial(snapshot, "/contents", guard));
            } catch (FetchFailure failure) {
                var directoryIssue = batch.failed(name, Stage.DIRECTORY, failure);
                if (directoryIssue.reason() == Reason.UNVERIFIED_REDIRECT && material.sha == null)
                    return new TrendCollection.Project(listed.withRepositoryId(null), null, "", directoryIssue);
                if (issue == null) issue = directoryIssue;
            }
        }
        return new TrendCollection.Project(classify(snapshot, files, material.status), material.sha, material.text, issue);
    }

    private record Readme(String sha, String text, String status) { }

    private Readme readme(TrendSourceTransport.Reply reply) {
        var material = object(reply);
        if (!"base64".equals(material.path("encoding").asText())
                || !material.path("size").isIntegralNumber() || !material.path("size").canConvertToInt()
                || material.path("size").asInt() < 0 || material.path("size").asInt() > 256 * 1024
                || !material.path("content").isTextual()
                || !material.path("sha").asText().matches("(?:[a-f0-9]{40}|[a-f0-9]{64})")) throw invalid();
        byte[] bytes;
        try {
            // 只去掉 GitHub base64 分行，其他非法字符直接拒绝。
            bytes = Base64.getDecoder().decode(material.path("content").asText().replace("\n", "").replace("\r", ""));
        } catch (IllegalArgumentException failure) { throw invalid(); }
        if (bytes.length != material.path("size").asInt()) throw invalid();
        String text = TrendReadmeExcerpt.select(new String(bytes, StandardCharsets.UTF_8));
        if (text.isBlank()) throw invalid();
        return new Readme(material.path("sha").asText(), text, "AVAILABLE");
    }

    private TrendSourceTransport.Reply get(URI initial, Runnable guard) {
        return get(initial, guard, null);
    }

    private TrendSourceTransport.Reply getMaterial(TrendProjectVO repo, String suffix, Runnable guard) {
        return get(URI.create("https://api.github.com/repos/" + repo.fullName() + suffix), guard,
                "/repositories/" + repo.repositoryId() + suffix);
    }

    private TrendSourceTransport.Reply get(URI initial, Runnable guard, String numericPath) {
        URI target = initial;
        boolean api = "api.github.com".equals(target.getHost());
        var cooldown = api ? apiRetryAt : listingRetryAt;
        for (int hop = 0; hop <= 2; hop++) {
            GithubTrendHttp.requireAllowed(target);
            guard.run();
            var until = cooldown.get();
            if (clock.instant().isBefore(until)) throw new FetchFailure(Reason.COOLDOWN, null, until);
            final TrendSourceTransport.Reply reply;
            try { reply = transport.get(target, api ? settings.githubToken() : null); }
            catch (GithubTrendHttp.BodyFailure failure) {
                throw new FetchFailure(failure.reason, failure.limits.status(), rememberWait(failure.limits, cooldown, false));
            }
            catch (BusinessException failure) { throw new FetchFailure(Reason.TRANSPORT_FAILURE, null, null); }
            int status = reply.status();
            var refusal = status == 403 && api ? refusalReason(reply.body()) : Reason.ACCESS_REFUSED;
            boolean limited = status == 429 || status == 403 && (reply.exhausted() || reply.retrySeconds() != null || refusal == Reason.RATE_LIMITED);
            Instant wait = rememberWait(reply, cooldown, limited);
            if (status == 200) return reply;
            if (limited) throw new FetchFailure(Reason.RATE_LIMITED, status, wait);
            if (status == 403 || status == 401) throw new FetchFailure(refusal, status, wait);
            if (status >= 500) throw new FetchFailure(Reason.UPSTREAM_ERROR, status, wait);
            if (!Set.of(301, 302, 307, 308).contains(status)) throw new FetchFailure(Reason.HTTP_ERROR, status, wait);
            // 跳转不能离开同一来源；每跳都复核允许路径与执行权。
            try {
                if (reply.location() == null || reply.location().length() > 512) throw invalid();
                URI next = target.resolve(reply.location());
                if (!Objects.equals(next.getHost(), target.getHost())) throw invalid();
                GithubTrendHttp.requireAllowed(next);
                target = next;
            } catch (BusinessException | IllegalArgumentException failure) {
                throw new FetchFailure(Reason.INVALID_RESPONSE, status, null);
            }
            // 元数据可通过返回的 ID 核验改名；正文和目录没有该字段，只跟随已核实仓库的同一资源。
            if (numericPath != null && !target.getRawPath().equalsIgnoreCase(initial.getRawPath())
                    && !target.getRawPath().equals(numericPath)) throw new FetchFailure(Reason.UNVERIFIED_REDIRECT, status, null);
        }
        throw new FetchFailure(Reason.INVALID_RESPONSE, null, null);
    }

    private Instant rememberWait(TrendSourceTransport.Reply reply, AtomicReference<Instant> cooldown, boolean limited) {
        Instant wait = reply.retrySeconds() == null ? null : clock.instant().plusSeconds(reply.retrySeconds());
        if (reply.exhausted() && reply.resetEpoch() != null) {
            var reset = Instant.ofEpochSecond(reply.resetEpoch());
            if (wait == null || reset.isAfter(wait)) wait = reset;
        }
        if ((limited || reply.exhausted()) && wait == null) wait = clock.instant().plusSeconds(60);
        return wait == null ? null : cooldown.accumulateAndGet(wait, (a, b) -> a.isAfter(b) ? a : b);
    }

    private Reason refusalReason(String body) {
        // GitHub 可在无等待头的 403 正文明确说明二级限流。未知说明保守暂停，原文不入日志。
        if (body == null || body.length() > 4096) return Reason.ACCESS_REFUSED;
        try {
            var error = json.readTree(body);
            String message = error == null ? "" : error.path("message").asText("");
            if (message.startsWith("You have exceeded a secondary rate limit")) return Reason.RATE_LIMITED;
            if (Set.of("Resource not accessible by integration", "Resource not accessible by personal access token").contains(message))
                return Reason.RESOURCE_ACCESS_REFUSED;
        } catch (java.io.IOException failure) { return Reason.ACCESS_REFUSED; }
        return Reason.ACCESS_REFUSED;
    }

    private JsonNode object(TrendSourceTransport.Reply reply) {
        var node = decode(reply);
        if (node == null || !node.isObject()) throw invalid();
        return node;
    }

    private JsonNode array(TrendSourceTransport.Reply reply) {
        var node = decode(reply);
        if (node == null || !node.isArray() || node.size() > 1000) throw invalid();
        return node;
    }

    private JsonNode decode(TrendSourceTransport.Reply reply) {
        try { return json.readTree(reply.body()); }
        catch (java.io.IOException failure) { throw invalid(); }
    }

    private TrendProjectVO metadata(TrendProjectVO base, JsonNode data) {
        String name = data.path("full_name").asText();
        if (!GithubTrendingParser.REPOSITORY.matcher(name).matches() || !data.path("id").isIntegralNumber() || !data.path("id").canConvertToLong()
                || data.path("id").asLong() <= 0 || !data.path("private").isBoolean()) throw invalid();
        if (data.path("private").asBoolean()) throw new FetchFailure(Reason.PRIVATE_REPOSITORY, 200, null);
        if (base.repositoryId() != null ? !base.repositoryId().equals(data.path("id").asText())
                : !name.equalsIgnoreCase(base.fullName())) throw new FetchFailure(Reason.IDENTITY_CONFLICT, 200, null);

        String license = string(data.path("license"), "spdx_id", 100);
        if ("NOASSERTION".equals(license)) license = null;
        return new TrendProjectVO(base.rankNum(), data.path("id").asText(), name, "https://github.com/" + name,
                Optional.ofNullable(string(data, "description", 2000)).orElse(base.description()), string(data, "language", 100),
                base.totalStars() == null ? metric(data.path("stargazers_count")) : base.totalStars(), base.starsAdded(),
                base.forksCount() == null ? metric(data.path("forks_count")) : base.forksCount(), license,
                data.path("archived").isBoolean() ? data.path("archived").asBoolean() : null,
                "UNKNOWN", "INSUFFICIENT_EVIDENCE", "AVAILABLE", "UNAVAILABLE", "NOT_ANALYZED");
    }

    private TrendProjectVO classify(TrendProjectVO item, JsonNode files, String readmeStatus) {
        Set<String> names = new HashSet<>();
        Set<String> dirs = new HashSet<>();
        // 根目录结果最多一千项；达到上限或含未识别条目时不能证明仓库只有资料。
        boolean complete = !files.isEmpty() && files.size() < 1000;
        for (var file : files) {
            String name = file.path("name").asText("").toLowerCase(Locale.ROOT);
            if (!file.path("name").isTextual() || name.isBlank()) complete = false;
            else if ("file".equals(file.path("type").asText())) names.add(name);
            else if ("dir".equals(file.path("type").asText())) dirs.add(name);
            else complete = false;
        }
        boolean build = names.stream().anyMatch(BUILDS::contains);
        boolean source = dirs.stream().anyMatch(Set.of("src", "lib", "app", "cmd", "crates", "packages")::contains);
        boolean namedResource = item.fullName().toLowerCase(Locale.ROOT)
                .matches(".*(?:awesome|interview|roadmap|tutorial|books).*" );
        boolean docsOnly = complete && dirs.stream().allMatch(Set.of("docs", "images", "assets", ".github", "examples")::contains)
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

    private static FetchFailure invalid() { return new FetchFailure(Reason.INVALID_RESPONSE, 200, null); }

    /** 每轮任务持有自己的诊断，首个局部错误与随后可能发生的整批暂停分别保存。 */
    private static final class Batch {
        final Instant captured;
        final Set<String> names = new HashSet<>();
        String firstRepo;
        TrendSourceIssue firstIssue;
        TrendSourceIssue pause;
        int skipped;
        int failures;

        Batch(Instant captured) { this.captured = captured; }

        TrendSourceIssue failed(String repo, Stage stage, FetchFailure failure) {
            var issue = new TrendSourceIssue(stage, failure.reason, failure.status, failure.retryAt);
            if (firstIssue == null) { firstRepo = repo; firstIssue = issue; }
            failures++;
            if (failure.pausesSource()) pause = issue;
            LOGGER.warn("星潮资料故障：任务={}，采集时间={}，仓库={}，故障={}", MDC.get("trendJob"), captured, repo, issue);
            return issue;
        }

    }

    /** 只传播安全分类；业务撤销异常不进入资料降级分支。 */
    private static final class FetchFailure extends BusinessException {
        final Reason reason;
        final Integer status;
        final Instant retryAt;

        FetchFailure(Reason reason, Integer status, Instant retryAt) {
            super(ErrorCode.SERVICE_UNAVAILABLE, "GitHub 资料暂时不可用");
            this.reason = reason;
            this.status = status;
            this.retryAt = retryAt;
        }

        boolean pausesSource() {
            return switch (reason) {
                case RATE_LIMITED, ACCESS_REFUSED, UPSTREAM_ERROR, TRANSPORT_FAILURE, COOLDOWN -> true;
                default -> false;
            };
        }
    }
}
