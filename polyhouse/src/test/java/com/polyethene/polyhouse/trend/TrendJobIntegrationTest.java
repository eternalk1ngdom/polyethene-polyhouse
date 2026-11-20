package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.config.*;
import com.polyethene.polyhouse.mapper.*;
import com.polyethene.polyhouse.model.entity.*;
import com.polyethene.polyhouse.service.*;
import com.polyethene.polyhouse.service.trend.*;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实MySQL验证任务围栏与原子发布；随机七表库，没有账号夹具、Redis或真实外部请求。 */
@EnabledIfSystemProperty(named = "polyhouse.trend.integration", matches = "true")
class TrendJobIntegrationTest {
    private static Sandbox sandbox;
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager manager;
    private static TrendJobMapper mapper;
    private static TrendMapper reports;
    private static TrendAiMapper aiMapper;
    private static TrendArchiveService archive;
    private TrendOwnerAuthorization owners;
    private TrendJobStore store;
    private User actor;

    @BeforeAll static void open() throws Exception {
        sandbox = new Sandbox();
        var source = new DriverManagerDataSource(sandbox.url, sandbox.user, sandbox.password);
        jdbc = new JdbcTemplate(source); manager = new DataSourceTransactionManager(source);
        var configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true); configuration.setArgNameBasedConstructorAutoMapping(true);
        configuration.addMapper(TrendJobMapper.class); configuration.addMapper(TrendMapper.class);
        configuration.addMapper(TrendAiMapper.class);
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(configuration);
        var session = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        mapper = session.getMapper(TrendJobMapper.class); reports = session.getMapper(TrendMapper.class);
        aiMapper = session.getMapper(TrendAiMapper.class);
        var reportService = new TrendReportService(reports, TrendFixture.json(), new TrendProperties(true, null));
        archive = new TrendArchiveService(reports, reportService, TrendFixture.json(), manager);
    }
    @BeforeEach void clearOurTables() {
        jdbc.update("UPDATE tool_trend_job_slot SET active_id=NULL,generation=0,last_accepted_at=NULL");
        jdbc.update("DELETE FROM tool_trend_job"); jdbc.update("DELETE FROM tool_trend_daily");
        jdbc.update("DELETE FROM tool_trend_report_version"); jdbc.update("DELETE FROM tool_trend_ai_request");
        jdbc.update("DELETE FROM tool_trend_ai_repository"); jdbc.update("DELETE FROM tool_trend_ai_month");
        owners = mock(TrendOwnerAuthorization.class);
        actor = new User(); actor.setId(42L); actor.setRole(com.polyethene.polyhouse.common.constant.UserRoleEnum.OWNER); actor.setCredentialVersion(0L);
        when(owners.lockActor(42, 0)).thenReturn(actor);
        store = store(new TrendJobProperties(true), new TrendAiProperties(false, null, null, null, null, null));
    }
    @AfterAll static void close() throws Exception { if (sandbox != null) sandbox.close(); }

    @Test void concurrentIntentsShareOnePersistentSlotAndSameUuidReplays() throws Exception {
        try (var workers = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var first = workers.submit(() -> { start.await(); return store.submit(UUID.randomUUID().toString(), actor); });
            var second = workers.submit(() -> { start.await(); return store.submit(UUID.randomUUID().toString(), actor); });
            start.countDown();
            var a = first.get(10, TimeUnit.SECONDS); var b = second.get(10, TimeUnit.SECONDS);
            assertThat(a.accepted()).isNotEqualTo(b.accepted()); assertThat(a.job().jobId()).isEqualTo(b.job().jobId());
            assertThat(store.submit(a.job().jobId(), actor).replayed()).isTrue();
            assertThat(mapper.count()).isEqualTo(1);
        }
    }

    @Test void queuedIntentSurvivesRestartAndRunningIntentIsNotReplayed() {
        String id = UUID.randomUUID().toString(); store.submit(id, actor);
        var restarted = store(new TrendJobProperties(true), new TrendAiProperties(false, null, null, null, null, null));
        var running = restarted.claim();
        assertThat(running.id()).isEqualTo(id); assertThat(restarted.claim()).isNull();
        restarted.heartbeat(running); restarted.guard(running);
        assertThat(store.query(id).state()).isEqualTo("RUNNING");
    }

    @Test void workerCompletesPersistentTaskThroughFakeAiLedgerAndAtomicPublishedReport() {
        store = store(new TrendJobProperties(true), TrendAiFixture.settings());
        var source = mock(GithubTrendClient.class);
        when(source.collect(any(Runnable.class), anySet())).thenAnswer(invocation -> { ((Runnable) invocation.getArgument(0)).run(); return TrendAiFixture.collection(); });
        var gateway = mock(DeepSeekGateway.class);
        when(gateway.complete(any())).thenReturn(TrendAiFixture.reply(TrendAiFixture.content(), 100, 20));
        var ai = new TrendAiService(new TrendAiLedger(aiMapper, TrendAiFixture.settings(), Clock.fixed(TrendAiFixture.NOW, ZoneOffset.UTC), manager),
                gateway, new TrendInsightCodec(TrendFixture.json()), TrendAiFixture.settings(), new TrendProperties(true, null), Clock.fixed(TrendAiFixture.NOW, ZoneOffset.UTC));
        String id = UUID.randomUUID().toString(); store.submit(id, actor);
        var runner = new TrendJobRunner(store, source, ai, mock(TrendReportService.class), new TrendProperties(true, null), new TrendJobProperties(true));
        try {
            runner.start(); runner.wake();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertThat(store.query(id).state()).isEqualTo("SUCCEEDED"));
            assertThat(reports.latest().id()).isEqualTo(id);
            assertThat(reports.latest().payload()).doesNotContain(TrendAiFixture.KEY, "private internal material");
            assertThat(jdbc.queryForObject("SELECT used_micros FROM tool_trend_ai_month WHERE month_key='2026-11'", Long.class)).isEqualTo(360);
            verify(gateway, times(1)).complete(any());
        } finally { runner.close(); }
    }

    @Test void expiredWorkerCannotRenewPublishOrReleaseNewGeneration() {
        String id = UUID.randomUUID().toString(); store.submit(id, actor); var old = store.claim();
        jdbc.update("UPDATE tool_trend_job SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?", id);
        assertThat(store.query(id).state()).isEqualTo("INTERRUPTED");
        ageCooldown();
        String next = UUID.randomUUID().toString(); store.submit(next, actor); var current = store.claim();
        assertThat(current.generation()).isGreaterThan(old.generation());
        assertThatThrownBy(() -> store.heartbeat(old)).isInstanceOf(TrendExecutionLost.class);
        assertThatThrownBy(() -> store.publish(old, TrendFixture.collection("2026-11-16T00:00:00Z"))).isInstanceOf(TrendExecutionLost.class);
        store.fail(old, true);
        assertThat(store.query(next).state()).isEqualTo("RUNNING");
        assertThat(reports.latest()).isNull();
    }

    @Test void deadlineAndLatestAccountStateRevokeExecutionBeforeFurtherCost() {
        String id = UUID.randomUUID().toString(); store.submit(id, actor); var job = store.claim();
        doThrow(new com.polyethene.polyhouse.common.exception.BusinessException(com.polyethene.polyhouse.common.constant.ErrorCode.FORBIDDEN))
                .when(owners).lockActor(42, 0);
        assertThatThrownBy(() -> store.guard(job)).isInstanceOf(TrendExecutionLost.class);
        reset(owners); when(owners.lockActor(42, 0)).thenReturn(actor);
        jdbc.update("UPDATE tool_trend_job SET deadline=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?", id);
        assertThatThrownBy(() -> store.heartbeat(job)).isInstanceOf(TrendExecutionLost.class);
        assertThat(store.query(id).state()).isEqualTo("INTERRUPTED");
        assertThat(store.claim()).isNull();
    }

    @Test void successfulBaseReportIsPartialAndNewIntentIsCooledDown() {
        String id = UUID.randomUUID().toString(); store.submit(id, actor); var job = store.claim();
        store.advance(job, "PUBLISHING");
        var report = store.publish(job, TrendFixture.collection("2026-11-16T00:00:00Z"));
        assertThat(report.reportId()).isEqualTo(id);
        assertThat(store.query(id).state()).isEqualTo("PARTIAL"); assertThat(store.query(id).phase()).isEqualTo("DONE");
        assertThat(store.submit(id, actor).replayed()).isTrue();
        assertThatThrownBy(() -> store.submit(UUID.randomUUID().toString(), actor)).isInstanceOf(TrendJobThrottle.class);
        assertThat(reports.latest().id()).isEqualTo(id);
    }

    @Test void failureAfterReportInsertRollsBackPublicPointerAndJobCompletionTogether() {
        String original = UUID.randomUUID().toString(); archive.publish(original, TrendFixture.collection("2026-11-16T00:00:00Z"));
        String id = UUID.randomUUID().toString(); store.submit(id, actor); var job = store.claim(); store.advance(job, "PUBLISHING");
        jdbc.execute("ALTER TABLE tool_trend_job ADD CONSTRAINT ck_fixture_completion CHECK (state <> 'PARTIAL')");
        try {
            assertThatThrownBy(() -> store.publish(job, TrendFixture.collection("2026-11-16T01:00:00Z")))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(reports.findVersion(id)).isNull(); assertThat(reports.latest().id()).isEqualTo(original);
            assertThat(store.query(id).state()).isEqualTo("RUNNING");
        } finally { jdbc.execute("ALTER TABLE tool_trend_job DROP CHECK ck_fixture_completion"); }
        store.fail(job, false);
        assertThat(store.query(id).state()).isEqualTo("FAILED"); assertThat(store.query(id).phase()).isEqualTo("PUBLISHING");
    }

    @Test void capacityAndRetentionNeverEraseContextWhileFeesArePending() {
        jdbc.execute("""
            INSERT INTO tool_trend_job(id,actor_id,credential_version,ai_requested,generation,state,phase,created_at,deadline,completed_at)
            WITH RECURSIVE n AS (SELECT 1 AS i UNION ALL SELECT i+1 FROM n WHERE i<1000)
            SELECT UUID(),42,0,FALSE,1,'FAILED','COLLECTING',UTC_TIMESTAMP(6)-INTERVAL 92 DAY,
            UTC_TIMESTAMP(6)-INTERVAL 91 DAY,UTC_TIMESTAMP(6)-INTERVAL 91 DAY FROM n
            """);
        jdbc.update("INSERT INTO tool_trend_ai_month(month_key,held_micros) VALUES ('2026-11',1)");
        jdbc.update("INSERT INTO tool_trend_ai_repository(repository_id) VALUES (1)");
        jdbc.update("""
            INSERT INTO tool_trend_ai_request(id,month_key,repository_id,readme_sha,model,rule_version,state,reserved_micros,
            input_bound,output_bound,input_rate,output_rate,created_at) VALUES (?, '2026-11',1,?,'deepseek-flash','radar-1','UNKNOWN',1,1,1,2,8,UTC_TIMESTAMP(6))
            """, UUID.randomUUID().toString(), TrendAiFixture.SHA);
        assertThatThrownBy(() -> store.submit(UUID.randomUUID().toString(), actor)).isInstanceOf(com.polyethene.polyhouse.common.exception.BusinessException.class);
        assertThat(mapper.count()).isEqualTo(1000);
        jdbc.update("DELETE FROM tool_trend_ai_request");
        assertThat(store.submit(UUID.randomUUID().toString(), actor).accepted()).isTrue();
        assertThat(mapper.count()).isEqualTo(1);
    }

    @Test void disabledMissingAiConfigurationAndMissingSlotRefuseAdmission() {
        assertThatThrownBy(() -> store(new TrendJobProperties(false), TrendAiFixture.settings()).submit(UUID.randomUUID().toString(), actor))
                .isInstanceOf(com.polyethene.polyhouse.common.exception.BusinessException.class);
        assertThatThrownBy(() -> store(new TrendJobProperties(true), new TrendAiProperties(true, null, null, null, null, null))
                .submit(UUID.randomUUID().toString(), actor)).isInstanceOf(com.polyethene.polyhouse.common.exception.BusinessException.class);
        assertThat(mapper.count()).isZero();
        jdbc.update("DELETE FROM tool_trend_job_slot");
        try { assertThatThrownBy(store::claim).isInstanceOf(com.polyethene.polyhouse.common.exception.BusinessException.class); }
        finally { jdbc.update("INSERT INTO tool_trend_job_slot(singleton_id) VALUES (1)"); }
    }

    @Test void migrationRollbackLeavesPublishedReportsAndAiLedgerIntact() throws Exception {
        archive.publish(UUID.randomUUID().toString(), TrendFixture.collection("2026-11-16T00:00:00Z"));
        jdbc.execute("DROP TABLE tool_trend_job_slot"); jdbc.execute("DROP TABLE tool_trend_job");
        assertThat(reports.latest()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tool_trend_ai_month", Long.class)).isZero();
        try (var connection = DriverManager.getConnection(sandbox.url, sandbox.user, sandbox.password)) { migrate(connection, "012_trend_jobs.sql"); }
        assertThat(store.submit(UUID.randomUUID().toString(), actor).accepted()).isTrue();
    }

    private static void ageCooldown() { jdbc.update("UPDATE tool_trend_job_slot SET last_accepted_at=UTC_TIMESTAMP(6)-INTERVAL 61 SECOND"); }
    private TrendJobStore store(TrendJobProperties jobs, TrendAiProperties ai) {
        return new TrendJobStore(mapper, owners, archive, new TrendProperties(true, null), jobs, ai, manager);
    }
    private static void migrate(Connection connection, String file) {
        ScriptUtils.executeSqlScript(connection, new EncodedResource(new FileSystemResource("sql/" + file), StandardCharsets.UTF_8));
    }
    /** 单独的本机随机库，删除前严格验证名字，始终不在现有polyhouse_db建表或创建屋主。 */
    private static final class Sandbox implements AutoCloseable {
        private final String name = "polyhouse_trend_job_" + UUID.randomUUID().toString().replace("-", "");
        private final String user = System.getenv("DB_USERNAME");
        private final String password = System.getenv("DB_PASSWORD");
        private final String direct;
        private final String url;
        private Sandbox() throws Exception {
            String configured = System.getenv().getOrDefault("DB_URL", "jdbc:mysql://localhost:3306/polyhouse_db");
            if (!configured.startsWith("jdbc:mysql://")) throw new IllegalStateException("Only local MySQL is allowed");
            URI parsed = URI.create(configured.substring(5));
            if (!List.of("localhost", "127.0.0.1").contains(parsed.getHost()) || !"/polyhouse_db".equals(parsed.getPath())
                    || parsed.getUserInfo() != null || user == null || password == null) throw new IllegalStateException("Invalid local sandbox configuration");
            direct = "jdbc:mysql://" + parsed.getHost() + ":" + (parsed.getPort() < 0 ? 3306 : parsed.getPort())
                    + "/?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=3000&socketTimeout=5000&serverTimezone=Asia/Shanghai";
            url = direct.replace("/?", "/" + name + "?");
            try (var connection = DriverManager.getConnection(direct, user, password); var command = connection.createStatement()) {
                command.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4"); connection.setCatalog(name);
                try { migrate(connection, "010_trend_reports.sql"); migrate(connection, "011_trend_ai.sql"); migrate(connection, "012_trend_jobs.sql"); }
                catch (RuntimeException failure) { try { close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); } throw failure; }
            }
        }
        @Override public void close() throws Exception {
            if (!name.matches("polyhouse_trend_job_[0-9a-f]{32}")) throw new IllegalStateException("Invalid sandbox name");
            try (var connection = DriverManager.getConnection(direct, user, password); var command = connection.createStatement()) {
                command.execute("DROP DATABASE IF EXISTS `" + name + "`");
            }
        }
    }
}
