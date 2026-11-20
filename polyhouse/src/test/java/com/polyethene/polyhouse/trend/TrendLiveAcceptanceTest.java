package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.config.TrendAiProperties;
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
        assertThat(settings.ready()).isTrue();
        assertThat(settings.monthlyBudgetMicros()).isEqualTo(100_000L);
        doAnswer(invocation -> {
            TrendCollection full = (TrendCollection) invocation.callRealMethod();
            var candidates = full.projects().stream().filter(project -> !"DOC_LIST".equals(project.snapshot().repoType())
                    && project.readmeVersion() != null && project.readmeText() != null && !project.readmeText().isBlank()).limit(2).toList();
            if (candidates.isEmpty()) throw new IllegalStateException("真实来源未取得可解读样本，不调用AI");
            return new TrendCollection(full.capturedAt(), candidates);
        }).when(source).collect(any(Runnable.class), anySet());
        doAnswer(invocation -> { paidCalls.incrementAndGet(); return invocation.callRealMethod(); }).when(gateway).complete(any());
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
        // 同月重复运行复用同一意图；失败也不自动换号重新收费，必要的新样本须另行明确安排。
        String id = UUID.nameUUIDFromBytes(("polyhouse-g4-real-sample-v1:" + month).getBytes(StandardCharsets.UTF_8)).toString();
        var submission = call("POST", "/api/manage/trend/jobs", token, Map.of("requestId", id));
        assertThat(submission.statusCode()).isEqualTo(202);
        String state;
        try { state = awaitTerminal(id, Duration.ofMinutes(6)); }
        catch (java.util.concurrent.TimeoutException timeout) {
            // 超时也保留脱敏诊断供核对，不能静默换意图或让原收费请求重发。
            state = jdbc.queryForObject("SELECT state FROM tool_trend_job WHERE id=?", String.class, id);
        }
        var progress = call("GET", "/api/manage/trend/jobs?jobId=" + id, token, null);
        var report = call("GET", "/api/trend/report", null, null);
        var fees = call("GET", "/api/manage/trend/fees", token, null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("capturedAt", java.time.Instant.now().toString());
        result.put("scope", "LOCAL_REAL_SAMPLE"); result.put("jobId", id); result.put("state", state);
        result.put("replayed", data(submission).path("replayed").asBoolean());
        result.put("paidCallsThisRun", paidCalls.get()); result.put("task", data(progress));
        result.put("report", report.statusCode() == 200 ? data(report) : null); result.put("fees", data(fees));
        result.put("requests", jdbc.queryForList("SELECT id,month_key,state,reserved_micros,charged_micros,prompt_tokens,completion_tokens,outcome FROM tool_trend_ai_request ORDER BY created_at"));
        Path output = Path.of("../.local-backups/trend-live/latest.json");
        Files.createDirectories(output.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), result);
        // 只输出经VO脱敏的报告与账本，不写Key、JWT、Prompt、README全文或上游响应。
        assertThat(paidCalls.get()).isBetween(0, 2);
        assertThat(state).isIn("SUCCEEDED", "PARTIAL");
        assertThat(report.statusCode()).isEqualTo(200);
        assertThat(data(report).path("items").size()).isBetween(1, 2);
        boolean analyzed = false;
        for (var item : data(report).path("items")) if ("ANALYZED".equals(item.path("insightStatus").asText())
                || "REUSED".equals(item.path("insightStatus").asText())) analyzed = true;
        assertThat(analyzed).as("至少一项真实解读须通过结构校验，内容质量仍待人工核对").isTrue();
        assertThat(data(fees).path("heldMicros").asLong()).isZero();
        assertThat(data(fees).path("accountedMicros").asLong()).isBetween(1L, 100_000L);
        assertThat(data(fees).path("pending").isEmpty()).isTrue();
    }
}
