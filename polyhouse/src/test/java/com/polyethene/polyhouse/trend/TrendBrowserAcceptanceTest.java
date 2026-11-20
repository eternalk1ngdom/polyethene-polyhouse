package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.model.entity.TrendCollection;
import com.polyethene.polyhouse.model.vo.TrendProjectVO;
import com.polyethene.polyhouse.service.TrendArchiveService;
import com.polyethene.polyhouse.service.TrendJobRunner;
import com.polyethene.polyhouse.service.trend.DeepSeekGateway;
import com.polyethene.polyhouse.service.trend.GithubTrendClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;

/** 浏览器连接真实鉴权、执行器、归档和费用接口，外部 AI 关闭，测试只写临时库。 */
@EnabledIfSystemProperty(named = "polyhouse.trend.browser", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TrendBrowserAcceptanceTest extends TrendHttpAcceptanceSupport {
    private static final String HISTORY_DAY = "2025-01-15";
    private static TrendAcceptanceSandbox sandbox;
    @Autowired private TrendArchiveService archive;
    @Autowired private TrendJobRunner runner;
    @SpyBean private GithubTrendClient github;
    @MockBean private DeepSeekGateway ai;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        sandbox = new TrendAcceptanceSandbox(false);
        sandbox.configure(registry, false);
        registry.add("polyhouse.trend.github-token", () -> "");
    }

    @AfterAll void releaseSandbox() throws Exception {
        if (runner != null) runner.close();
        if (sandbox != null) sandbox.close();
    }

    @Test void ownerPublishesAndAnonymousReadsThroughTheActualBrowser() throws Exception {
        String historyId = UUID.randomUUID().toString();
        archive.publish(historyId, new TrendCollection(Instant.parse(HISTORY_DAY + "T00:00:00Z"), List.of(project(1))));
        long owner = actor(UserRoleEnum.OWNER, false);
        String username = jdbc.queryForObject("SELECT username FROM sys_user WHERE id=?", String.class, owner);
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger projects = new AtomicInteger();
        boolean live = Boolean.getBoolean("polyhouse.trend.browser.live-github");
        var previous = TrendAiFixture.insight(TrendAiFixture.NOW);
        if (!live) {
            // 发布后从匿名接口核对同仓库的成功旧稿，防止仅验证“没有扣费”漏掉整期缺文。
            jdbc.update("INSERT INTO tool_trend_ai_repository(repository_id, payload) VALUES(?, ?)",
                    2L, TrendFixture.json().writeValueAsString(previous));
        }
        doAnswer(invocation -> {
            if (attempts.incrementAndGet() != 1) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
            TrendCollection result;
            if (live) result = (TrendCollection) invocation.callRealMethod();
            else {
                invocation.<Runnable>getArgument(0).run();
                result = new TrendCollection(Instant.now(), IntStream.rangeClosed(1, 25).mapToObj(TrendBrowserAcceptanceTest::project).toList());
            }
            projects.set(result.projects().size());
            return result;
        }).when(github).collect(any(Runnable.class), anySet());

        Process browser = browser(username);
        try {
            // 首份日报已经提交后，仅缩短临时库的冷却时间，失败分支仍由真实执行器处理。
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(4);
            while (browser.isAlive() && System.nanoTime() < deadline) {
                if (count("SELECT COUNT(*) FROM tool_trend_job WHERE state IN ('PARTIAL','SUCCEEDED')") == 1) {
                    expireCooldown();
                    break;
                }
                Thread.sleep(250);
            }
            assertThat(browser.waitFor(75, TimeUnit.SECONDS)).as("浏览器联调超时").isTrue();
            assertThat(browser.exitValue()).as("浏览器联调未通过，见 target/trend-browser-result.log").isZero();
        } finally {
            if (browser.isAlive()) {
                browser.descendants().forEach(ProcessHandle::destroyForcibly);
                browser.destroyForcibly();
                browser.waitFor(5, TimeUnit.SECONDS);
            }
        }

        assertThat(projects.get()).isBetween(1, 25);
        assertThat(attempts.get()).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM tool_trend_job WHERE state='FAILED'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM tool_trend_report_version")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM tool_trend_ai_request")).isZero();
        verifyNoInteractions(ai);
        var report = data(call("GET", "/api/trend/report", null, null));
        assertThat(report.path("reportId").asText()).isNotEqualTo(historyId);
        assertThat(report.path("items").size()).isEqualTo(projects.get());
        if (!live) {
            var items = report.path("items");
            assertThat(items.get(0).path("insight").isNull() || items.get(0).path("insight").isMissingNode()).isTrue();
            assertThat(items.get(1).path("insightStatus").asText()).isEqualTo("STALE");
            assertThat(TrendFixture.json().treeToValue(items.get(1).get("insight"), previous.getClass())).isEqualTo(previous);
        }
        org.slf4j.LoggerFactory.getLogger(getClass()).info("星潮浏览器验收通过：{}日榜 {} 项，无 AI 请求", live ? "GitHub 实时" : "固定", projects.get());
    }

    private Process browser(String username) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("node", "scripts/verify-trend-browser.mjs")
                .directory(Path.of("../polyhouse-web").toFile()).redirectErrorStream(true);
        var inherited = Map.copyOf(builder.environment());
        builder.environment().clear();
        // 子进程只拿本机工具路径和随机库账号，禁止把真实数据库凭据或模型 Key 交给浏览器。
        for (String key : List.of("PATH", "SystemRoot", "TEMP", "TMP", "USERPROFILE", "LOCALAPPDATA",
                "ProgramFiles", "ProgramFiles(x86)", "POLYHOUSE_PLAYWRIGHT_MODULE")) {
            inherited.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(key)).findFirst()
                    .ifPresent(entry -> builder.environment().put(entry.getKey(), entry.getValue()));
        }
        builder.environment().put("POLYHOUSE_TREND_BROWSER_PORT", Integer.toString(port));
        builder.environment().put("POLYHOUSE_TREND_BROWSER_USER", username);
        builder.environment().put("POLYHOUSE_TREND_BROWSER_PASSWORD", PASSWORD);
        builder.environment().put("POLYHOUSE_TREND_BROWSER_HISTORY", HISTORY_DAY);
        return builder.redirectOutput(Path.of("target/trend-browser-result.log").toAbsolutePath().toFile()).start();
    }

    private int count(String sql) { return jdbc.queryForObject(sql, Integer.class); }

    private static TrendCollection.Project project(int rank) {
        String name = "sample/browser-project-" + rank;
        var snapshot = new TrendProjectVO(rank, Integer.toString(rank), name, "https://github.com/" + name,
                "Browser acceptance fixture", rank % 2 == 0 ? "TypeScript" : "Java", 10L, 2L, 1L, "MIT",
                false, "CODE_PROJECT", "BUILD_AND_SOURCE", "AVAILABLE", "MISSING", "NOT_ANALYZED");
        return new TrendCollection.Project(snapshot, null, "");
    }
}
