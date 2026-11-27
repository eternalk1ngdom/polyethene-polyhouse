package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.config.*;
import com.polyethene.polyhouse.mapper.*;
import com.polyethene.polyhouse.model.entity.*;
import com.polyethene.polyhouse.model.dto.TrendReportQueryDTO;
import com.polyethene.polyhouse.service.*;
import com.polyethene.polyhouse.service.trend.*;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.AdditionalAnswers;
import org.mybatis.spring.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.script.RedisScript;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实随机九表库和可控数据库时钟，验证自动受理/安全补偿/系统身份；不使用真实账号或付费AI。 */
@EnabledIfSystemProperty(named = "polyhouse.trend.integration", matches = "true")
class TrendScheduleIntegrationTest {
    private static final LocalDateTime START = LocalDateTime.parse("2026-11-15T22:30:00");
    private static Sandbox sandbox;
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager manager;
    private static TrendJobMapper realJobs;
    private static TrendScheduleMapper scheduled;
    private static TrendAiMapper aiMapper;
    private static TrendMapper reports;
    private static TrendArchiveService archive;
    private TrendJobMapper jobs;
    private TrendOwnerAuthorization owners;
    private TrendJobStore store;
    private final AtomicReference<LocalDateTime> now = new AtomicReference<>();

    @BeforeAll static void open() throws Exception {
        sandbox = new Sandbox();
        var source = new DriverManagerDataSource(sandbox.url, sandbox.user, sandbox.password);
        jdbc = new JdbcTemplate(source); manager = new DataSourceTransactionManager(source);
        var config = new Configuration(); config.setMapUnderscoreToCamelCase(true); config.setArgNameBasedConstructorAutoMapping(true);
        for (var type : List.of(TrendMapper.class, TrendJobMapper.class, TrendScheduleMapper.class, TrendAiMapper.class)) config.addMapper(type);
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(config);
        var session = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        realJobs = session.getMapper(TrendJobMapper.class); scheduled = session.getMapper(TrendScheduleMapper.class);
        aiMapper = session.getMapper(TrendAiMapper.class); reports = session.getMapper(TrendMapper.class);
        archive = new TrendArchiveService(reports, new TrendReportService(reports, TrendFixture.json(), new TrendProperties(true, null)), TrendFixture.json(), manager);
    }
    @BeforeEach void clearOurTables() {
        jdbc.update("UPDATE tool_trend_job_slot SET active_id=NULL,generation=0,last_accepted_at=NULL");
        for (String table : List.of("tool_trend_schedule", "tool_trend_ai_resolution", "tool_trend_job", "tool_trend_daily", "tool_trend_report_version",
                "tool_trend_ai_request", "tool_trend_ai_repository", "tool_trend_ai_month")) jdbc.update("DELETE FROM " + table); // 固定测试表白名单。
        now.set(START);
        jobs = mock(TrendJobMapper.class, AdditionalAnswers.delegatesTo(realJobs)); doAnswer(invocation -> now.get()).when(jobs).now();
        owners = mock(TrendOwnerAuthorization.class); when(owners.lockActor(42, 0)).thenReturn(TrendFixture.owner());
        store = service(new TrendScheduleProperties(true, null, null));
    }
    @AfterAll static void close() throws Exception { if (sandbox != null) sandbox.close(); }

    @Test void firstWindowIsBeijingAndSystemIdentityHasExplicitPersistentAuthorization() {
        now.set(START.minusSeconds(1)); assertThat(store.scheduleDue()).isNull(); now.set(START);
        var job = store.scheduleDue(); assertThat(job.actorId()).isNull(); assertThat(job.credentialVersion()).isNull();
        assertThat(scheduled.byJob(job.id()).periodDate()).isEqualTo(LocalDate.of(2026, 11, 16));
        assertThat(store.query(job.id()).trigger()).isEqualTo("SCHEDULED");
        var running = store.claim(); store.guard(running); verify(owners, never()).lockActor(anyLong(), anyLong());
        assertThatThrownBy(() -> store.submit(job.id(), TrendFixture.owner())).isInstanceOf(com.polyethene.polyhouse.common.exception.BusinessException.class);
    }

    @Test void concurrentInstancesAndRestartAdmitOnlyOneFirstIntent() throws Exception {
        try (var workers = Executors.newFixedThreadPool(2)) {
            var other = service(new TrendScheduleProperties(true, null, null)); var start = new CountDownLatch(1);
            var a = workers.submit(() -> { start.await(); return store.scheduleDue(); });
            var b = workers.submit(() -> { start.await(); return other.scheduleDue(); }); start.countDown();
            var results = Arrays.asList(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertThat(results.stream().filter(Objects::nonNull).count()).isEqualTo(1);
        }
        assertThat(service(new TrendScheduleProperties(true, null, null)).scheduleDue()).isNull();
        assertThat(realJobs.count()).isEqualTo(1);
    }

    @Test void collectionFailureAllowsOneCompensationAndNeverAThird() {
        store.scheduleDue(); var first = store.claim(); store.fail(first, false);
        now.set(START.plusMinutes(45)); var second = store.scheduleDue(); assertThat(second).isNotNull();
        assertThat(scheduled.byJob(second.id()).attempt()).isEqualTo(1); var running = store.claim(); store.fail(running, false);
        now.set(START.plusMinutes(47)); assertThat(store.scheduleDue()).isNull(); assertThat(realJobs.count()).isEqualTo(2);
    }

    @ParameterizedTest @ValueSource(strings = {"ANALYZING", "PUBLISHING", "INTERRUPTED"})
    void unsafeOrInterruptedFirstExecutionCannotBeAutomaticallyRepeated(String phase) {
        store.scheduleDue(); var first = store.claim();
        if (phase.equals("INTERRUPTED")) { now.set(START.plusMinutes(3)); store.query(first.id()); }
        else { store.advance(first, phase); store.fail(first, false); }
        now.set(START.plusMinutes(45)); assertThat(store.scheduleDue()).isNull(); assertThat(realJobs.count()).isEqualTo(1);
    }

    @Test void pendingBillingBlocksCompensationEvenWhenFirstFailedBeforeAi() {
        store.scheduleDue(); var first = store.claim(); store.fail(first, false);
        var ledger = new TrendAiLedger(aiMapper, TrendAiFixture.settings(), Clock.fixed(TrendAiFixture.NOW, ZoneOffset.UTC), manager);
        String id = UUID.randomUUID().toString(); ledger.reserve(id, 1, TrendAiFixture.SHA, 1000, 100, null); ledger.unknown(id, false);
        now.set(START.plusMinutes(45)); assertThat(store.scheduleDue()).isNull(); assertThat(realJobs.count()).isEqualTo(1);
    }

    @Test void missedWindowsDoNotBackfillAndManualPublishedReportPreventsAutomaticVersion() {
        now.set(START.plusMinutes(10)); assertThat(store.scheduleDue()).isNull();
        now.set(START.plusMinutes(45)); assertThat(store.scheduleDue()).isNull();
        archive.publish(UUID.randomUUID().toString(), TrendFixture.collection("2026-11-15T22:00:00Z"));
        now.set(START); assertThat(store.scheduleDue()).isNull();
        now.set(START.plusDays(1)); var next = store.scheduleDue();
        assertThat(scheduled.byJob(next.id()).periodDate()).isEqualTo(LocalDate.of(2026, 11, 17));
        assertThat(realJobs.count()).isEqualTo(1);
    }

    @Test void manualAndScheduledAdmissionShareSlotAndCooldown() {
        String id = UUID.randomUUID().toString(); store.submit(id, TrendFixture.owner()); assertThat(store.scheduleDue()).isNull();
        var manual = store.claim(); store.fail(manual, false); assertThat(store.scheduleDue()).isNull();
        now.set(START.plusMinutes(2)); assertThat(store.scheduleDue()).isNotNull(); assertThat(realJobs.count()).isEqualTo(2);
    }

    @Test void disabledWorkerCannotStealSystemQueueAndCannotAuthorizeRunningSystemJob() {
        var job = store.scheduleDue(); var disabled = service(new TrendScheduleProperties(false, null, null));
        assertThat(disabled.claim()).isNull(); assertThat(store.query(job.id()).state()).isEqualTo("QUEUED");
        var running = store.claim(); assertThat(running.id()).isEqualTo(job.id());
        assertThatThrownBy(() -> disabled.guard(running)).isInstanceOf(TrendExecutionLost.class);
        verify(owners, never()).lockActor(anyLong(), anyLong());
    }

    @Test void absentSystemIntentAndMixedNullIdentityAreRejected() {
        String id = UUID.randomUUID().toString(); store.submit(id, TrendFixture.owner());
        assertThatThrownBy(() -> jdbc.update("UPDATE tool_trend_job SET actor_id=NULL WHERE id=?", id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        jdbc.update("UPDATE tool_trend_job SET actor_id=NULL,credential_version=NULL WHERE id=?", id);
        var running = store.claim(); assertThatThrownBy(() -> store.guard(running)).isInstanceOf(TrendExecutionLost.class);
    }

    @Test void systemWorkerPublishesWithSimulatedAiAndRedisFailureStillReadsPublishedDatabase() {
        var source = mock(GithubTrendClient.class);
        when(source.collect(any(Runnable.class), anySet())).thenAnswer(invocation -> { ((Runnable) invocation.getArgument(0)).run(); return TrendAiFixture.collection(); });
        var gateway = mock(DeepSeekGateway.class); when(gateway.complete(any())).thenReturn(TrendAiFixture.reply(TrendAiFixture.content(), 100, 20));
        var ai = new TrendAiService(new TrendAiLedger(aiMapper, TrendAiFixture.settings(), Clock.fixed(TrendAiFixture.NOW, ZoneOffset.UTC), manager),
                gateway, new TrendInsightCodec(TrendFixture.json()), TrendAiFixture.settings(), new TrendProperties(true, null), Clock.fixed(TrendAiFixture.NOW, ZoneOffset.UTC));
        var runner = new TrendJobRunner(store, source, ai, mock(TrendReportService.class), new TrendProperties(true, null), new TrendJobProperties(true));
        var daily = new TrendDailyScheduler(store, runner, new TrendProperties(true, null), new TrendJobProperties(true), new TrendScheduleProperties(true, null, null));
        try {
            runner.start(); daily.start();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
                assertThat(reports.latestHeader()).isNotNull();
                assertThat(store.query(reports.latestHeader().id()).state()).isEqualTo("SUCCEEDED");
            });
            String publishedId = reports.latestHeader().id();
            var redis = mock(StringRedisTemplate.class);
            when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<String>>any(), anyList(), anyString(), anyString()))
                    .thenThrow(new DataAccessResourceFailureException("private cache failure"));
            var cache = new TrendReportCache(redis, new TrendCacheProperties(true), Clock.fixed(TrendAiFixture.NOW, ZoneOffset.UTC));
            var reader = new TrendReportService(reports, TrendFixture.json(), new TrendProperties(true, null), cache);
            assertThat(reader.report(new TrendReportQueryDTO(null, null)).reportId()).isEqualTo(publishedId);
            assertThat(reader.report(new TrendReportQueryDTO(null, "2026-11-16")).insightComplete()).isTrue();
            verify(owners, never()).lockActor(anyLong(), anyLong()); verify(gateway, times(1)).complete(any());
        } finally { daily.close(); runner.close(); }
    }

    @Test void migrationRollbackRequiresRemovingOnlyOurSystemFactsAndPreservesManualAndReports() throws Exception {
        String manual = UUID.randomUUID().toString(); store.submit(manual, TrendFixture.owner()); var running = store.claim(); store.fail(running, false);
        now.set(START.plusMinutes(2)); store.scheduleDue(); var system = store.claim(); store.fail(system, false);
        String published = UUID.randomUUID().toString(); archive.publish(published, TrendFixture.collection("2026-11-15T22:32:01Z"));
        jdbc.update("DELETE FROM tool_trend_job WHERE actor_id IS NULL");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tool_trend_schedule", Long.class)).isZero();
        jdbc.execute("DROP TABLE tool_trend_schedule");
        jdbc.execute("ALTER TABLE tool_trend_job DROP CHECK ck_trend_job_actor_source, MODIFY actor_id BIGINT NOT NULL, MODIFY credential_version BIGINT NOT NULL, ADD CONSTRAINT ck_trend_job_actor CHECK(actor_id>0 AND credential_version>=0 AND generation>0)");
        assertThat(realJobs.count()).isEqualTo(1); assertThat(realJobs.lockJob(manual).actorId()).isEqualTo(42L);
        assertThat(reports.latestHeader().id()).isEqualTo(published);
        try (var connection = DriverManager.getConnection(sandbox.url, sandbox.user, sandbox.password)) { migrate(connection, "014_trend_schedule.sql"); }
    }

    @Test void migration018KillSwitchBlocksAutomaticSchedulePreservesManualAndRollsBackCleanly() throws Exception {
        // 1. 未迁移 018 时：只读状态安全回退为未暂停，写暂停返回明确 503。
        assertThat(store.scheduleStatus().schedulePaused()).isFalse();
        assertThatThrownBy(() -> store.setSchedulePaused(true))
                .isInstanceOfSatisfying(com.polyethene.polyhouse.common.exception.BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(com.polyethene.polyhouse.common.constant.ErrorCode.SERVICE_UNAVAILABLE));

        // 2. 执行 018 正向迁移：人工急停阻断定时窗口受理，但不影响屋主手动更新；恢复后定时窗口重新生效。
        try (var connection = DriverManager.getConnection(sandbox.url, sandbox.user, sandbox.password)) {
            migrate(connection, "018_trend_schedule_pause.sql");
        }
        try {
            var paused = store.setSchedulePaused(true);
            assertThat(paused.schedulePaused()).isTrue();
            assertThat(paused.todayStatus()).isEqualTo("PAUSED");
            assertThat(store.scheduleDue()).isNull();

            // 手动更新不受自动采集急停影响。
            String manualId = UUID.randomUUID().toString();
            assertThat(store.submit(manualId, TrendFixture.owner()).accepted()).isTrue();
            var manualRunning = store.claim();
            store.fail(manualRunning, false);

            // 恢复自动采集后，过冷却期在下一天窗口可正常自动受理。
            var resumed = store.setSchedulePaused(false);
            assertThat(resumed.schedulePaused()).isFalse();
            now.set(START.plusDays(1));
            var autoJob = store.scheduleDue();
            assertThat(autoJob).isNotNull();
            assertThat(scheduled.byJob(autoJob.id()).periodDate()).isEqualTo(LocalDate.of(2026, 11, 17));
        } finally {
            // 3. 逆序回退 018 还原随机库基线。
            jdbc.execute("ALTER TABLE tool_trend_job_slot DROP COLUMN schedule_paused");
        }
        assertThat(store.scheduleStatus().schedulePaused()).isFalse();
    }

    private TrendJobStore service(TrendScheduleProperties settings) {
        return new TrendJobStore(jobs, owners, archive, new TrendProperties(true, null), new TrendJobProperties(true), TrendAiFixture.settings(), manager, settings, scheduled);
    }
    private static void migrate(Connection connection, String file) {
        ScriptUtils.executeSqlScript(connection, new EncodedResource(new FileSystemResource("sql/" + file), StandardCharsets.UTF_8));
    }
    /** 固定本机数据库基线，创建独立随机库，删除前验证完整名字，不碰现有OWNER。 */
    private static final class Sandbox implements AutoCloseable {
        private final String name = "polyhouse_trend_schedule_" + UUID.randomUUID().toString().replace("-", "");
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
                try { for (String file : List.of("010_trend_reports.sql", "011_trend_ai.sql", "012_trend_jobs.sql", "013_trend_ai_resolution.sql", "014_trend_schedule.sql")) migrate(connection, file); }
                catch (RuntimeException failure) { try { close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); } throw failure; }
            }
        }
        @Override public void close() throws Exception {
            if (!name.matches("polyhouse_trend_schedule_[0-9a-f]{32}")) throw new IllegalStateException("Invalid sandbox name");
            try (var connection = DriverManager.getConnection(direct, user, password); var command = connection.createStatement()) { command.execute("DROP DATABASE IF EXISTS `" + name + "`"); }
        }
    }
}
