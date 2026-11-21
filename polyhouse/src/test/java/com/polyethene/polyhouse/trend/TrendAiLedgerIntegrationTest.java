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
import org.mybatis.spring.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/** 真实MySQL事务与模拟AI组合验证，不读取真实DS Key、不产生付费、不写既有账号库。 */
@EnabledIfSystemProperty(named = "polyhouse.trend.integration", matches = "true")
class TrendAiLedgerIntegrationTest {
    private static Sandbox sandbox;
    private static TrendAiMapper mapper;
    private static TrendMapper reportsMapper;
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager manager;
    private static TrendInsightCodec codec;

    @BeforeAll static void open() throws Exception {
        sandbox = new Sandbox();
        var source = new DriverManagerDataSource(sandbox.url, sandbox.user, sandbox.password);
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        var configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setArgNameBasedConstructorAutoMapping(true);
        configuration.addMapper(TrendAiMapper.class);
        configuration.addMapper(TrendMapper.class);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        var session = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        mapper = session.getMapper(TrendAiMapper.class);
        reportsMapper = session.getMapper(TrendMapper.class);
        codec = new TrendInsightCodec(TrendFixture.json());
    }
    @BeforeEach void clearOnlyOurTables() {
        jdbc.update("DELETE FROM tool_trend_daily");
        jdbc.update("DELETE FROM tool_trend_report_version");
        jdbc.update("DELETE FROM tool_trend_ai_request");
        jdbc.update("DELETE FROM tool_trend_ai_repository");
        jdbc.update("DELETE FROM tool_trend_ai_month");
    }
    @AfterAll static void close() throws Exception { if (sandbox != null) sandbox.close(); }

    @Test void reservationIsPersistentAndSameUuidOrRepositoryCannotBeChargedTwice() {
        var ledger = ledger(TrendAiFixture.NOW, TrendAiFixture.settings());
        String id = UUID.randomUUID().toString();
        assertThat(ledger.reserve(id, 1, TrendAiFixture.SHA, 1000, 100, null).status()).isEqualTo("GRANTED");
        assertThat(ledger.reserve(id, 1, TrendAiFixture.SHA, 1000, 100, null).status()).isEqualTo("REPLAY");
        assertThat(ledger.reserve(UUID.randomUUID().toString(), 1, TrendAiFixture.SHA, 1000, 100, null).status()).isEqualTo("PENDING");
        assertThat(number("held_micros")).isEqualTo(2800);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tool_trend_ai_request", Long.class)).isEqualTo(1);
    }

    @Test void concurrentReservationsNeverOverdrawSharedMonthBudget() throws Exception {
        var settings = new TrendAiProperties(true, TrendAiFixture.KEY, null, null, null, 3000L);
        var ledger = ledger(TrendAiFixture.NOW, settings);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            List<Future<String>> results = new ArrayList<>();
            for (long repo = 1; repo <= 2; repo++) {
                long repository = repo;
                results.add(workers.submit(() -> {
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    return ledger.reserve(UUID.randomUUID().toString(), repository, TrendAiFixture.SHA, 1000, 100, null).status();
                }));
            }
            start.countDown();
            List<String> states = new ArrayList<>();
            for (var result : results) states.add(result.get(10, TimeUnit.SECONDS));
            assertThat(states).containsExactlyInAnyOrder("GRANTED", "BUDGET_LIMIT");
        }
        assertThat(number("held_micros")).isEqualTo(2800);
    }

    @Test void settlesExactlyOnceAndPublishesCacheInTheSameTransaction() {
        var ledger = ledger(TrendAiFixture.NOW, TrendAiFixture.settings());
        String id = UUID.randomUUID().toString();
        ledger.reserve(id, 1, TrendAiFixture.SHA, 1000, 100, null);
        String payload = codec.encode(TrendAiFixture.insight(TrendAiFixture.NOW));
        ledger.settle(id, 100, 20, payload);
        ledger.settle(id, 100, 20, payload);
        assertThat(number("used_micros")).isEqualTo(360);
        assertThat(number("held_micros")).isZero();
        assertThat(mapper.repository(1).activeRequest()).isNull();
        assertThat(codec.cached(mapper.repository(1).payload()).definition()).isEqualTo("本地开发辅助工具");
    }

    @Test void cacheWriteFailureRollsBackFeeSettlement() {
        var ledger = ledger(TrendAiFixture.NOW, TrendAiFixture.settings());
        String id = UUID.randomUUID().toString();
        ledger.reserve(id, 1, TrendAiFixture.SHA, 1000, 100, null);
        assertThatThrownBy(() -> ledger.settle(id, 100, 20, "not-json")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(number("used_micros")).isZero();
        assertThat(number("held_micros")).isEqualTo(2800);
        assertThat(mapper.request(id).state()).isEqualTo("RESERVED");
        assertThat(mapper.repository(1).activeRequest()).isEqualTo(id);
    }

    @Test void unknownResultsSurviveRestartCannotBeRefundedAndUsageViolationSuspendsMonth() {
        var ledger = ledger(TrendAiFixture.NOW, TrendAiFixture.settings());
        String id = UUID.randomUUID().toString();
        ledger.reserve(id, 1, TrendAiFixture.SHA, 1000, 100, null);
        ledger.unknown(id, true);
        var restarted = ledger(TrendAiFixture.NOW, TrendAiFixture.settings());
        assertThat(restarted.reserve(UUID.randomUUID().toString(), 1, TrendAiFixture.SHA, 1000, 100, null).status()).isEqualTo("PENDING");
        assertThat(restarted.reserve(UUID.randomUUID().toString(), 2, TrendAiFixture.SHA, 1000, 100, null).status()).isEqualTo("BUDGET_LIMIT");
        assertThatThrownBy(() -> restarted.cancelBeforeSend(id)).isInstanceOf(com.polyethene.polyhouse.common.exception.BusinessException.class);
        assertThat(number("held_micros")).isEqualTo(2800);
        assertThat(mapper.request(id).state()).isEqualTo("UNKNOWN");
    }

    @Test void provenNotSentRefundsButInvalidOutputWithUsageIsStillCharged() {
        var ledger = ledger(TrendAiFixture.NOW, TrendAiFixture.settings());
        String id = UUID.randomUUID().toString();
        ledger.reserve(id, 1, TrendAiFixture.SHA, 1000, 100, null);
        ledger.cancelBeforeSend(id);
        assertThat(number("held_micros")).isZero();
        assertThat(number("used_micros")).isZero();
        assertThat(mapper.request(id).state()).isEqualTo("CANCELLED");
        String next = UUID.randomUUID().toString();
        ledger.reserve(next, 1, TrendAiFixture.SHA, 1000, 100, null);
        ledger.settle(next, 100, 20, null);
        assertThat(number("used_micros")).isEqualTo(360);
        assertThat(mapper.repository(1).payload()).isNull();
    }

    @Test void crossMonthCompletionSettlesTheOriginalBeijingMonth() {
        var old = ledger(Instant.parse("2026-11-30T15:59:59Z"), TrendAiFixture.settings());
        String id = UUID.randomUUID().toString();
        old.reserve(id, 1, TrendAiFixture.SHA, 1000, 100, null);
        var nextMonth = ledger(Instant.parse("2026-11-30T16:00:00Z"), TrendAiFixture.settings());
        nextMonth.reserve(UUID.randomUUID().toString(), 2, TrendAiFixture.SHA, 1000, 100, null);
        nextMonth.settle(id, 100, 20, null);
        assertThat(jdbc.queryForObject("SELECT used_micros FROM tool_trend_ai_month WHERE month_key=?", Long.class, "2026-11")).isEqualTo(360);
        assertThat(jdbc.queryForObject("SELECT held_micros FROM tool_trend_ai_month WHERE month_key=?", Long.class, "2026-12")).isEqualTo(2800);
    }

    @Test void fakeProviderAnalysisReusesCacheAndHistoricalReportRemainsFrozen() {
        var ledger = ledger(TrendAiFixture.NOW, TrendAiFixture.settings());
        var calls = new AtomicInteger();
        DeepSeekGateway fake = body -> { calls.incrementAndGet(); return TrendAiFixture.reply(TrendAiFixture.content(), 100, 20); };
        var service = new TrendAiService(ledger, fake, codec, TrendAiFixture.settings(), new TrendProperties(true, null), Clock.fixed(TrendAiFixture.NOW, ZoneOffset.UTC));
        var enriched = service.enrich(UUID.randomUUID().toString(), TrendAiFixture.collection());
        assertThat(enriched.projects().getFirst().snapshot().insightStatus()).isEqualTo("ANALYZED");
        var reports = new TrendReportService(reportsMapper, TrendFixture.json(), new TrendProperties(true, null));
        var archive = new TrendArchiveService(reportsMapper, reports, TrendFixture.json(), manager);
        String reportId = UUID.randomUUID().toString();
        archive.publish(reportId, enriched);
        assertThat(reports.report(new TrendReportQueryDTO(null, null)).insightComplete()).isTrue();
        assertThat(service.enrich(UUID.randomUUID().toString(), TrendAiFixture.collection()).projects().getFirst().snapshot().insightStatus()).isEqualTo("REUSED");
        assertThat(calls.get()).isEqualTo(1);
        jdbc.update("UPDATE tool_trend_ai_repository SET payload=NULL WHERE repository_id=?", 1);
        assertThat(reports.report(new TrendReportQueryDTO(null, null)).items().getFirst().insight().definition()).isEqualTo("本地开发辅助工具");
        assertThat(reportsMapper.findVersion(reportId).payload()).doesNotContain(TrendAiFixture.KEY, "Ignore all prior");
    }

    @Test void databaseConstraintsAndRollbackProtectTheTenYuanBoundary() throws Exception {
        mapper.ensureMonth("2026-11");
        assertThatThrownBy(() -> jdbc.update("UPDATE tool_trend_ai_month SET used_micros=10000001 WHERE month_key=?", "2026-11"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        jdbc.execute("DROP TABLE tool_trend_ai_request");
        jdbc.execute("DROP TABLE tool_trend_ai_repository");
        jdbc.execute("DROP TABLE tool_trend_ai_month");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tool_trend_daily", Long.class)).isZero();
        try (var connection = DriverManager.getConnection(sandbox.url, sandbox.user, sandbox.password)) { migrate(connection, "011_trend_ai.sql"); }
        assertThat(ledger(TrendAiFixture.NOW, TrendAiFixture.settings()).reserve(UUID.randomUUID().toString(), 1, TrendAiFixture.SHA, 1000, 100, null).status()).isEqualTo("GRANTED");
        assertThat(TrendAiLedger.cost(1, 1, new BigDecimal("2.1"), new BigDecimal("8"))).isEqualTo(11);
    }

    private static TrendAiLedger ledger(Instant now, TrendAiProperties settings) { return new TrendAiLedger(mapper, settings, Clock.fixed(now, ZoneOffset.UTC), manager); }
    private static long number(String field) {
        if (!Set.of("used_micros", "held_micros").contains(field)) throw new IllegalArgumentException("Unknown fixed test field");
        return jdbc.queryForObject("SELECT " + field + " FROM tool_trend_ai_month WHERE month_key='2026-11'", Long.class);
    }
    private static void migrate(Connection connection, String file) {
        ScriptUtils.executeSqlScript(connection, new EncodedResource(new FileSystemResource("sql/" + file), StandardCharsets.UTF_8));
    }
    /** 与G1分离的随机五表库；名字由内部生成，删除前再次验证，失败不遗留半建库。 */
    private static final class Sandbox implements AutoCloseable {
        private final String name = "polyhouse_trend_ai_" + UUID.randomUUID().toString().replace("-", "");
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
                command.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4");
                connection.setCatalog(name);
                try { migrate(connection, "010_trend_reports.sql"); migrate(connection, "011_trend_ai.sql"); }
                catch (RuntimeException failure) { try { close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); } throw failure; }
            }
        }
        @Override public void close() throws Exception {
            if (!name.matches("polyhouse_trend_ai_[0-9a-f]{32}")) throw new IllegalStateException("Invalid sandbox name");
            try (var connection = DriverManager.getConnection(direct, user, password); var command = connection.createStatement()) {
                command.execute("DROP DATABASE IF EXISTS `" + name + "`");
            }
        }
    }
}
