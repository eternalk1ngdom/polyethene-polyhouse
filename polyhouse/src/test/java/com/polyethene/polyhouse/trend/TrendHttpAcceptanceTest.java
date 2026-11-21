package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.model.entity.TrendCollection;
import com.polyethene.polyhouse.service.TrendJobRunner;
import com.polyethene.polyhouse.service.TrendArchiveService;
import com.polyethene.polyhouse.service.trend.DeepSeekGateway;
import com.polyethene.polyhouse.service.trend.GithubTrendClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 真实Controller/JWT/Redis/MySQL/执行线程闭环，仅GitHub和AI边界使用假响应，不产生外部费用。 */
@EnabledIfSystemProperty(named = "polyhouse.trend.http", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TrendHttpAcceptanceTest extends TrendHttpAcceptanceSupport {
    private static TrendAcceptanceSandbox sandbox;
    @Autowired private TrendJobRunner runner;
    @Autowired private TrendArchiveService archive;
    @MockBean private GithubTrendClient source;
    @MockBean private DeepSeekGateway gateway;
    private final AtomicInteger collected = new AtomicInteger();
    private final AtomicBoolean fail = new AtomicBoolean();
    private CountDownLatch entered;
    private CountDownLatch release;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        sandbox = new TrendAcceptanceSandbox(false); sandbox.configure(registry);
    }

    @BeforeEach void prepareExternalFixtures() {
        reset(source, gateway);
        collected.set(0); fail.set(false); entered = new CountDownLatch(1); release = new CountDownLatch(0);
        when(source.collect(any(Runnable.class), anySet())).thenAnswer(invocation -> {
            Runnable guard = invocation.getArgument(0);
            guard.run(); collected.incrementAndGet(); entered.countDown();
            if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("隔离来源等待超时");
            guard.run();
            if (fail.get()) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
            return new TrendCollection(Instant.now(), List.of(TrendAiFixture.project(1, TrendAiFixture.SHA)));
        });
        when(gateway.complete(any())).thenReturn(TrendAiFixture.reply(TrendAiFixture.content(), 1000, 300));
    }

    @AfterEach void clearOnlyOurDatabaseAndRedis() throws Exception {
        release.countDown();
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (jdbc.queryForObject("SELECT COUNT(*) FROM tool_trend_job WHERE state IN ('QUEUED','RUNNING')", Integer.class) > 0
                && System.nanoTime() < deadline) Thread.sleep(100);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tool_trend_job WHERE state IN ('QUEUED','RUNNING')", Integer.class)).isZero();
        clearOwnedRedisKeys();
        jdbc.update("UPDATE tool_trend_job_slot SET active_id=NULL,last_accepted_at=NULL");
        // 仅清理本次随机库的固定表白名单，先删除外键引用；真实费用库不使用此夹具。
        for (String table : List.of("tool_trend_schedule", "tool_trend_ai_resolution", "tool_trend_job", "tool_trend_daily",
                "tool_trend_report_version", "tool_trend_ai_request", "tool_trend_ai_repository", "tool_trend_ai_month",
                "sys_user_growth_reward", "sys_user")) jdbc.update("DELETE FROM " + table);
    }

    @AfterAll void dropRandomDatabase() throws Exception {
        if (runner != null) runner.close();
        if (sandbox != null) sandbox.close();
    }

    @Test void anonymousReadsAndAllNonOwnerRolesCannotTriggerOrInspectPaidWork() throws Exception {
        assertThat(call("GET", "/api/trend/periods", null, null).statusCode()).isEqualTo(200);
        assertThat(call("GET", "/api/trend/report", null, null).statusCode()).isEqualTo(404);
        assertThat(call("POST", "/api/manage/trend/jobs", null, Map.of("requestId", UUID.randomUUID().toString())).statusCode()).isEqualTo(401);
        for (var role : List.of(UserRoleEnum.USER, UserRoleEnum.ADMIN, UserRoleEnum.INSIDER)) {
            String token = login(actor(role, false));
            assertThat(call("POST", "/api/manage/trend/jobs", token, Map.of("requestId", UUID.randomUUID().toString())).statusCode()).isEqualTo(403);
            assertThat(call("GET", "/api/manage/trend/fees", token, null).statusCode()).isEqualTo(403);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tool_trend_job", Integer.class)).isZero();
        verifyNoInteractions(source, gateway);
    }

    @Test void acceptedReplayBusyPublishFeesAndRealContentCacheFormOneHttpFlow() throws Exception {
        String token = login(actor(UserRoleEnum.OWNER, false));
        release = new CountDownLatch(1);
        String id = submit(token);
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        var replay = call("POST", "/api/manage/trend/jobs", token, Map.of("requestId", id));
        assertThat(data(replay).path("replayed").asBoolean()).isTrue();
        var busy = call("POST", "/api/manage/trend/jobs", token, Map.of("requestId", UUID.randomUUID().toString()));
        assertThat(data(busy).path("accepted").asBoolean()).isFalse();
        assertThat(data(busy).path("job").path("jobId").asText()).isEqualTo(id);
        release.countDown();
        assertThat(awaitTerminal(id)).isEqualTo("SUCCEEDED");
        var progress = call("GET", "/api/manage/trend/jobs?jobId=" + id, token, null);
        assertThat(progress.statusCode()).isEqualTo(200);
        assertThat(data(progress).path("state").asText()).isEqualTo("SUCCEEDED");
        assertThat(progress.headers().firstValue("Cache-Control")).contains("no-store");
        var report = call("GET", "/api/trend/report", null, null);
        assertThat(report.statusCode()).isEqualTo(200);
        assertThat(data(report).path("insightComplete").asBoolean()).isTrue();
        assertThat(data(report).path("items").get(0).path("insightStatus").asText()).isEqualTo("ANALYZED");
        assertThat(report.body()).doesNotContain(TrendAiFixture.KEY, "readmeText", "credentialVersion", "passwordHash");
        assertThat(redis.opsForHash().get(CACHE_KEY, "id")).isEqualTo(data(report).path("reportId").asText());
        assertThat(redis.getExpire(CACHE_KEY)).isBetween(1L, 900L);
        var fees = call("GET", "/api/manage/trend/fees", token, null);
        assertThat(fees.statusCode()).isEqualTo(200);
        assertThat(data(fees).path("accountedMicros").asLong()).isPositive();
        assertThat(data(fees).path("heldMicros").asLong()).isZero();
        assertThat(data(fees).path("pending").isEmpty()).isTrue();
        assertThat(collected.get()).isEqualTo(1); verify(gateway, times(1)).complete(any());
    }

    @Test void longEditionSurvivesRealRedisCorruptionAndAnonymousReadBack() throws Exception {
        String id = UUID.randomUUID().toString();
        archive.publish(id, TrendAiFixture.longEdition());
        var first = data(call("GET", "/api/trend/report", null, null));
        assertThat(first.path("items").size()).isEqualTo(25);
        assertThat(redis.opsForHash().get(CACHE_KEY, "id")).isEqualTo(id);
        redis.opsForHash().put(CACHE_KEY, "payload", "{");
        assertThat(data(call("GET", "/api/trend/report", null, null))).isEqualTo(first);
        assertThat(redis.opsForHash().get(CACHE_KEY, "payload").toString()).contains(id);
        verifyNoInteractions(source, gateway);
    }

    @Test void sameReadmeReusesInsightNewVersionPublishesAndFailedUpdateKeepsOldReport() throws Exception {
        String token = login(actor(UserRoleEnum.OWNER, false));
        assertThat(awaitTerminal(submit(token))).isEqualTo("SUCCEEDED");
        String first = data(call("GET", "/api/trend/report", null, null)).path("reportId").asText();
        expireCooldown(); assertThat(awaitTerminal(submit(token))).isEqualTo("SUCCEEDED");
        var latest = data(call("GET", "/api/trend/report", null, null));
        assertThat(latest.path("reportId").asText()).isNotEqualTo(first);
        assertThat(latest.path("items").get(0).path("insightStatus").asText()).isEqualTo("REUSED");
        var older = data(call("GET", "/api/manage/trend/jobs?jobId=" + first, token, null)).path("summary");
        var newer = data(call("GET", "/api/manage/trend/jobs?jobId=" + latest.path("reportId").asText(), token, null)).path("summary");
        assertThat(older.path("generated").intValue()).isOne();
        assertThat(older.path("reused").intValue()).isZero();
        assertThat(newer.path("reused").intValue()).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tool_trend_report_version", Integer.class)).isEqualTo(2);
        fail.set(true); expireCooldown(); assertThat(awaitTerminal(submit(token))).isEqualTo("FAILED");
        assertThat(data(call("GET", "/api/trend/report", null, null)).path("reportId").asText()).isEqualTo(latest.path("reportId").asText());
        verify(gateway, times(1)).complete(any());
    }

    @Test void credentialRevocationDuringCollectionPreventsAiAndPublishing() throws Exception {
        long owner = actor(UserRoleEnum.OWNER, false);
        String token = login(owner);
        release = new CountDownLatch(1); String id = submit(token);
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        jdbc.update("UPDATE sys_user SET credential_version=credential_version+1 WHERE id=?", owner);
        release.countDown(); assertThat(awaitTerminal(id)).isEqualTo("INTERRUPTED");
        assertThat(call("GET", "/api/manage/trend/jobs?jobId=" + id, token, null).statusCode()).isEqualTo(401);
        assertThat(call("GET", "/api/trend/report", null, null).statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tool_trend_ai_request", Integer.class)).isZero();
        verifyNoInteractions(gateway);
    }

    @Test void initialPasswordLogoutAndLatestRoleAreCheckedWithRealSessions() throws Exception {
        long owner = actor(UserRoleEnum.OWNER, true);
        String temporary = login(owner);
        assertThat(call("POST", "/api/manage/trend/jobs", temporary, Map.of("requestId", UUID.randomUUID().toString())).statusCode()).isEqualTo(403);
        jdbc.update("UPDATE sys_user SET password_change_required=0 WHERE id=?", owner);
        String token = login(owner);
        jdbc.update("UPDATE sys_user SET role=? WHERE id=?", UserRoleEnum.ADMIN.getCode(), owner);
        assertThat(call("POST", "/api/manage/trend/jobs", token, Map.of("requestId", UUID.randomUUID().toString())).statusCode()).isEqualTo(403);
        jdbc.update("UPDATE sys_user SET role=? WHERE id=?", UserRoleEnum.OWNER.getCode(), owner);
        assertThat(call("POST", "/api/auth/logout", token, Map.of("token", token)).statusCode()).isEqualTo(200);
        assertThat(call("GET", "/api/manage/trend/fees", token, null).statusCode()).isEqualTo(401);
        verifyNoInteractions(source, gateway);
    }

    @Test void unknownAiChargeStaysHeldAndPublicBaseReportDoesNotClaimAnalysis() throws Exception {
        when(gateway.complete(any())).thenThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE));
        String token = login(actor(UserRoleEnum.OWNER, false));
        assertThat(awaitTerminal(submit(token))).isEqualTo("PARTIAL");
        var report = data(call("GET", "/api/trend/report", null, null));
        assertThat(report.path("insightComplete").asBoolean()).isFalse();
        assertThat(report.path("items").get(0).path("insight").isNull()).isTrue();
        var fees = data(call("GET", "/api/manage/trend/fees", token, null));
        assertThat(fees.path("heldMicros").asLong()).isPositive();
        assertThat(fees.path("pending").size()).isEqualTo(1);
        assertThat(fees.path("pending").get(0).path("state").asText()).isEqualTo("UNKNOWN");
        verify(gateway, times(1)).complete(any());
    }
}
