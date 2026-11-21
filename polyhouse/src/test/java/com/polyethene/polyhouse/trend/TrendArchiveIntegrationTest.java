package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.config.TrendProperties;
import com.polyethene.polyhouse.mapper.TrendMapper;
import com.polyethene.polyhouse.model.dto.*;
import com.polyethene.polyhouse.service.TrendArchiveService;
import com.polyethene.polyhouse.service.TrendReportService;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 只建随机两表隔离库，不加载账号初始化，不向既有开发库写入任何夹具。 */
@EnabledIfSystemProperty(named = "polyhouse.trend.integration", matches = "true")
class TrendArchiveIntegrationTest {
    private static Sandbox sandbox;
    private static TrendMapper mapper;
    private static TrendReportService reports;
    private static TrendArchiveService archive;
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager manager;

    @BeforeAll static void open() throws Exception {
        sandbox = new Sandbox();
        var dataSource = new DriverManagerDataSource(sandbox.url, sandbox.user, sandbox.password);
        var configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setArgNameBasedConstructorAutoMapping(true);
        configuration.addMapper(TrendMapper.class);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        mapper = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject())).getMapper(TrendMapper.class);
        reports = new TrendReportService(mapper, TrendFixture.json(), new TrendProperties(true, null));
        manager = new DataSourceTransactionManager(dataSource);
        archive = new TrendArchiveService(mapper, reports, TrendFixture.json(), manager);
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach void clearOnlyOurTwoTables() {
        jdbc.update("DELETE FROM tool_trend_daily");
        jdbc.update("DELETE FROM tool_trend_report_version");
    }

    @AfterAll static void close() throws Exception { if (sandbox != null) sandbox.close(); }

    @Test void publishesBeijingDateKeepsHistoryAndReplaysWithoutRepublishingOldVersion() {
        String first = UUID.randomUUID().toString();
        var before = TrendFixture.collection("2026-11-15T23:59:59.123456789Z");
        archive.publish(first, before);
        var initial = reports.report(new TrendReportQueryDTO(null, null));
        assertThat(initial.periodKey()).isEqualTo(LocalDate.of(2026, 11, 16));
        String second = UUID.randomUUID().toString();
        archive.publish(second, TrendFixture.collection("2026-11-16T00:01:00Z"));
        assertThat(archive.publish(first, before).reportId()).isEqualTo(first);
        assertThat(reports.report(new TrendReportQueryDTO(null, "2026-11-16")).reportId()).isEqualTo(second);
        assertThat(mapper.findVersion(first).payload()).doesNotContain("private internal material");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tool_trend_report_version", Integer.class)).isEqualTo(2);
        assertThat(reports.periods(new TrendPeriodsQueryDTO(null, null, null)).items()).containsExactly(LocalDate.of(2026, 11, 16));
    }

    @Test void failedOrStaleUpdatesLeavePublishedVersionUntouched() {
        String id = UUID.randomUUID().toString();
        archive.publish(id, TrendFixture.collection("2026-11-16T01:00:00Z"));
        assertThatThrownBy(() -> archive.publish(UUID.randomUUID().toString(), TrendFixture.collection("2026-11-16T00:00:00Z")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> archive.publish(id, TrendFixture.collection("2026-11-17T01:00:00Z")))
                .isInstanceOf(BusinessException.class);
        assertThat(reports.report(new TrendReportQueryDTO(null, null)).reportId()).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tool_trend_daily", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tool_trend_report_version", Integer.class)).isEqualTo(1);
    }

    @Test void completeEditionWithTwentyFiveLongArticlesSurvivesPublicationAndReadBack() throws Exception {
        var collection = TrendAiFixture.longEdition();
        var article = collection.projects().getFirst().snapshot().insight();
        archive.publish(UUID.randomUUID().toString(), collection);
        var report = reports.report(new TrendReportQueryDTO(null, null));
        assertThat(report.insightComplete()).isTrue();
        assertThat(report.items()).hasSize(25).allSatisfy(item -> assertThat(item.insight()).isEqualTo(article));
    }

    @Test void concurrentReplayCreatesExactlyOneVersion() throws Exception {
        String id = UUID.randomUUID().toString();
        var collection = TrendFixture.collection("2026-11-16T02:00:00Z");
        try (var workers = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) results.add(workers.submit(() -> {
                assertThat(gate.await(5, TimeUnit.SECONDS)).isTrue();
                return archive.publish(id, collection).reportId();
            }));
            gate.countDown();
            for (var result : results) assertThat(result.get(10, TimeUnit.SECONDS)).isEqualTo(id);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tool_trend_report_version", Integer.class)).isEqualTo(1);
    }

    @Test void olderDateDoesNotBecomeLatestAndCursorIsExclusive() {
        archive.publish(UUID.randomUUID().toString(), TrendFixture.collection("2026-11-16T02:00:00Z"));
        archive.publish(UUID.randomUUID().toString(), TrendFixture.collection("2026-11-14T02:00:00Z"));
        archive.publish(UUID.randomUUID().toString(), TrendFixture.collection("2026-11-15T02:00:00Z"));
        assertThat(reports.report(new TrendReportQueryDTO(null, null)).periodKey()).isEqualTo(LocalDate.of(2026, 11, 16));
        var first = reports.periods(new TrendPeriodsQueryDTO(null, null, 2));
        assertThat(first.items()).containsExactly(LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 15));
        var next = reports.periods(new TrendPeriodsQueryDTO(null, first.nextBefore().toString(), 2));
        assertThat(next.items()).containsExactly(LocalDate.of(2026, 11, 14));
        assertThat(next.nextBefore()).isNull();
    }

    @Test void publicationFailureRollsBackBothCandidateAndDayPointer() {
        var failing = mock(TrendMapper.class, org.mockito.AdditionalAnswers.delegatesTo(mapper));
        doReturn(0).when(failing).publish(any(), any());
        var failingArchive = new TrendArchiveService(failing, reports, TrendFixture.json(), manager);
        assertThatThrownBy(() -> failingArchive.publish(UUID.randomUUID().toString(), TrendFixture.collection("2026-11-16T02:00:00Z")))
                .isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tool_trend_daily", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tool_trend_report_version", Integer.class)).isZero();
    }

    @Test void migrationForeignKeyCheckAndRollbackOnlyAffectOurModule() throws Exception {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO tool_trend_report_version VALUES(?,?,?,?)",
                UUID.randomUUID().toString(), "2026-11-16", "2026-11-16 00:00:00", "[]")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO tool_trend_daily VALUES(?,?)", "2026-11-16", UUID.randomUUID().toString()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        String reportId = UUID.randomUUID().toString();
        archive.publish(reportId, TrendFixture.collection("2026-11-16T02:00:00Z"));
        jdbc.update("UPDATE tool_trend_daily SET report_id=NULL WHERE period_date=?", "2026-11-16");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO tool_trend_daily VALUES(?,?)", "2026-11-17", reportId))
                .isInstanceOfSatisfying(org.springframework.dao.DataAccessException.class, failure ->
                        assertThat(failure.getMostSpecificCause()).isInstanceOfSatisfying(SQLException.class,
                                sql -> assertThat(sql.getErrorCode()).isEqualTo(1452)));
        jdbc.execute("DROP TABLE tool_trend_daily");
        jdbc.execute("DROP TABLE tool_trend_report_version");
        assertThat(jdbc.queryForList("SHOW TABLES")).isEmpty();
        try (var connection = DriverManager.getConnection(sandbox.url, sandbox.user, sandbox.password)) {
            migrate(connection);
        }
        archive.publish(UUID.randomUUID().toString(), TrendFixture.collection("2026-11-16T02:00:00Z"));
        assertThat(reports.report(new TrendReportQueryDTO(null, null)).items()).hasSize(2);
    }

    private static void migrate(Connection connection) {
        ScriptUtils.executeSqlScript(connection, new EncodedResource(new FileSystemResource("sql/010_trend_reports.sql"), StandardCharsets.UTF_8));
    }

    private static final class Sandbox implements AutoCloseable {
        private final String name = "polyhouse_trend_" + UUID.randomUUID().toString().replace("-", "");
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
                // 数据库标识符不能参数绑定；名字完全由固定前缀和随机十六进制构造。
                command.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4");
                connection.setCatalog(name);
                try { migrate(connection); }
                catch (RuntimeException failure) {
                    try { close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                    throw failure;
                }
            }
        }

        @Override public void close() throws Exception {
            if (!name.matches("polyhouse_trend_[0-9a-f]{32}")) throw new IllegalStateException("Invalid sandbox name");
            try (var connection = DriverManager.getConnection(direct, user, password); var command = connection.createStatement()) {
                command.execute("DROP DATABASE IF EXISTS `" + name + "`");
            }
        }
    }
}
