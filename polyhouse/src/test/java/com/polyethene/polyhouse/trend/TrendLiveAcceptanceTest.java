package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.config.TrendAiProperties;
import com.polyethene.polyhouse.config.TrendProperties;
import com.polyethene.polyhouse.mapper.TrendMapper;
import com.polyethene.polyhouse.service.TrendReportService;
import com.polyethene.polyhouse.service.trend.GithubTrendingParser;
import com.polyethene.polyhouse.service.trend.TrendSourceTransport;
import com.polyethene.polyhouse.model.entity.TrendCollection;
import com.polyethene.polyhouse.model.dto.RegisterDTO;
import com.polyethene.polyhouse.service.TrendJobRunner;
import com.polyethene.polyhouse.service.trend.DeepSeekHttp;
import com.polyethene.polyhouse.service.trend.GithubTrendClient;
import com.polyethene.polyhouse.utils.PasswordUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.doAnswer;

/** 显式隐藏输入后才执行：真实来源和模型、至多两个项目、持久一角月预算，绝不清空真实账本。 */
@EnabledIfSystemProperty(named = "polyhouse.trend.live.acceptance", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TrendLiveAcceptanceTest extends TrendHttpAcceptanceSupport {
    private static TrendAcceptanceSandbox sandbox;
    @Autowired private TrendJobRunner runner;
    @Autowired private TrendAiProperties settings;
    @Autowired private TrendSourceTransport transport;
    @Autowired private TrendMapper reports;
    @Autowired private TrendReportService reader;
    @SpyBean private GithubTrendClient source;
    @SpyBean private DeepSeekHttp gateway;
    private final AtomicInteger paidCalls = new AtomicInteger();

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        String key = System.getenv("POLYHOUSE_DEEPSEEK_API_KEY");
        if (!"true".equals(System.getenv("POLYHOUSE_TREND_LIVE_CNY")) || key == null || !key.matches("[A-Za-z0-9_-]{16,256}")) {
            throw new IllegalArgumentException("真实验收仅经隐藏输入包装且已核对人民币计价时开启，不接受Key命令参数");
        }
        sandbox = new TrendAcceptanceSandbox(true); sandbox.configure(registry);
    }

    @AfterAll void retainFeesAndStopOnlyThisRun() throws Exception {
        if (runner != null) runner.close();
        if (jdbc != null && redis != null) clearOwnedRedisKeys();
        if (sandbox != null) sandbox.close();
    }

    @Test void realOwnerHttpTaskProducesReviewableSamplesAndPersistentCharges() throws Exception {
        boolean editorial = "true".equals(System.getenv("POLYHOUSE_TREND_EDITORIAL_SAMPLE"));
        assertThat(settings.ready()).isTrue();
        assertThat(settings.monthlyBudgetMicros()).isEqualTo(100_000L);
        doAnswer(invocation -> {
            if (editorial) return editorialSource(invocation.getArgument(0));
            TrendCollection full = (TrendCollection) invocation.callRealMethod();
            var candidates = full.projects().stream().filter(project -> !"DOC_LIST".equals(project.snapshot().repoType())
                    && project.readmeVersion() != null && project.readmeText() != null && !project.readmeText().isBlank()).limit(2).toList();
            if (candidates.isEmpty()) throw new IllegalStateException("真实来源未取得可解读样本，不调用AI");
            return new TrendCollection(full.capturedAt(), candidates);
        }).when(source).collect(any(Runnable.class), anySet());
        int maxCalls = editorial ? 1 : 2;
        doAnswer(invocation -> {
            if (paidCalls.incrementAndGet() > maxCalls) throw new AssertionError("超出本次授权样本数");
            return invocation.callRealMethod();
        }).when(gateway).complete(any());
        String username = "g4_live_owner";
        String fixturePassword = "G4!" + UUID.randomUUID();
        var existing = jdbc.queryForList("SELECT id FROM sys_user WHERE username=?", Long.class, username);
        long owner;
        if (existing.isEmpty()) {
            owner = Long.parseLong(auth.register(new RegisterDTO(username, fixturePassword, "真实小样本验收账号")).id());
            jdbc.update("UPDATE sys_user SET role=? WHERE id=?", UserRoleEnum.OWNER.getCode(), owner);
        } else {
            owner = existing.getFirst();
            assertThat(jdbc.queryForObject("SELECT role FROM sys_user WHERE id=?", String.class, owner)).isEqualTo(UserRoleEnum.OWNER.getCode());
            // 专属验收账号每次使用内存随机密码并撤销旧版本，不留下固定可调用付费接口的凭据。
            jdbc.update("UPDATE sys_user SET password_hash=?,credential_version=credential_version+1 WHERE id=?",
                    PasswordUtils.hashPassword(fixturePassword), owner);
        }
        String token = login(owner, fixturePassword);
        String month = YearMonth.now(ZoneId.of("Asia/Shanghai")).toString();
        // 同月重复运行复用同一意图；轻度思考对照单独区分意图，失败也不自动换号重新收费。
        String variant = settings.thinkingActive() ? "radar-4-low" : "radar-4";
        String intent = editorial ? "polyhouse-editorial-" + variant + "-cursor-2026-11" : "polyhouse-g4-real-sample-v1:" + month;
        if (editorial) assertThat(month).as("本次授权仅限十一月验收额度").isEqualTo("2026-11");
        String id = UUID.nameUUIDFromBytes(intent.getBytes(StandardCharsets.UTF_8)).toString();
        var submission = call("POST", "/api/manage/trend/jobs", token, Map.of("requestId", id));
        assertThat(submission.statusCode()).isEqualTo(202);
        String state;
        try { state = awaitTerminal(id, Duration.ofMinutes(6)); }
        catch (java.util.concurrent.TimeoutException timeout) {
            // 超时也保留脱敏诊断供核对，不能静默换意图或让原收费请求重发。
            state = jdbc.queryForObject("SELECT state FROM tool_trend_job WHERE id=?", String.class, id);
        }
        var progress = call("GET", "/api/manage/trend/jobs?jobId=" + id, token, null);
        var published = reports.findVersion(id);
        var report = published == null ? null : reader.decode(published);
        var fees = call("GET", "/api/manage/trend/fees", token, null);
        boolean replayed = data(submission).path("replayed").asBoolean();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("capturedAt", java.time.Instant.now().toString());
        result.put("scope", editorial ? "EDITORIAL_SINGLE" : "LOCAL_REAL_SAMPLE"); result.put("jobId", id); result.put("state", state);
        result.put("thinkingEnabled", settings.thinkingActive());
        result.put("replayed", replayed);
        result.put("paidCallsThisRun", paidCalls.get()); result.put("task", data(progress));
        result.put("report", report); result.put("fees", data(fees));
        result.put("requests", jdbc.queryForList("SELECT id,month_key,state,reserved_micros,charged_micros,prompt_tokens,completion_tokens,outcome FROM tool_trend_ai_request ORDER BY created_at"));
        Path output = Path.of("../.local-backups/trend-live", editorial ? "editorial-" + variant + ".json" : "latest.json");
        Files.createDirectories(output.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), result);
        // 只输出经VO脱敏的报告与账本，不写Key、JWT、Prompt、README全文或上游响应。
        assertThat(paidCalls.get()).isBetween(0, maxCalls);
        assertThat(state).isIn("SUCCEEDED", "PARTIAL");
        assertThat(report).isNotNull();
        // 新发布须能经匿名入口读到；重放旧意图时同日可能已有新版，此时核对上面按任务取出的原快照。
        if (!replayed) {
            var visible = call("GET", "/api/trend/report?periodKey=" + report.periodKey(), null, null);
            assertThat(visible.statusCode()).isEqualTo(200);
            assertThat(data(visible).path("reportId").asText()).isEqualTo(id);
        }
        assertThat(report.items()).hasSizeBetween(1, maxCalls).anyMatch(item ->
                "ANALYZED".equals(item.insightStatus()) || "REUSED".equals(item.insightStatus()));
        if (editorial) {
            var item = report.items().getFirst();
            assertThat(item.fullName()).isEqualTo("cursor/plugins");
            assertThat(item.insightStatus()).isEqualTo("ANALYZED");
            assertThat(item.insight().ruleVersion()).isEqualTo(com.polyethene.polyhouse.service.trend.TrendInsightCodec.RULE);
        }
        assertThat(data(fees).path("heldMicros").asLong()).isZero();
        assertThat(data(fees).path("accountedMicros").asLong()).isBetween(1L, 100_000L);
        assertThat(data(fees).path("pending").isEmpty()).isTrue();
    }

    private TrendCollection editorialSource(Runnable guard) {
        // 单项目验收使用留存的仓库身份和榜单星标定位样本；元数据、README 和目录走正式 GitHub 传输。
        String name = "cursor/plugins";
        String listing = "<h1>Trending</h1><article class='Box-row'><h2><a href='/cursor/plugins'>cursor/plugins</a></h2>"
                + "<a href='/cursor/plugins/stargazers'>17639</a></article>";
        TrendSourceTransport selected = (uri, token) -> GithubTrendingParser.SOURCE.equals(uri.toString())
                ? new TrendSourceTransport.Reply(200, listing, null) : transport.get(uri, token);
        var sample = new GithubTrendClient(selected, new GithubTrendingParser(), json, new TrendProperties(true, null))
                .collect(guard, java.util.Set.of());
        var project = sample.projects().getFirst();
        if (!name.equals(project.snapshot().fullName()) || !"1140229822".equals(project.snapshot().repositoryId())
                || !"AVAILABLE".equals(project.snapshot().metadataStatus()) || project.readmeVersion() == null || project.readmeText().isBlank()) {
            throw new IllegalStateException("未取得同一公开仓库的真实材料，停止本次样本");
        }
        return sample;
    }
}
