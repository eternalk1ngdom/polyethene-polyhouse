package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.config.TrendAiProperties;
import com.polyethene.polyhouse.config.TrendJobProperties;
import com.polyethene.polyhouse.config.TrendProperties;
import com.polyethene.polyhouse.mapper.*;
import com.polyethene.polyhouse.model.dto.TrendFeeQueryDTO;
import com.polyethene.polyhouse.model.dto.TrendFeeResolutionQueryDTO;
import com.polyethene.polyhouse.model.dto.TrendFeeResolveDTO;
import com.polyethene.polyhouse.service.*;
import com.polyethene.polyhouse.service.trend.TrendBriefingCodec;
import com.polyethene.polyhouse.service.trend.TrendOwnerAuthorization;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实隔离 MySQL 验证 015 迁移与回退、共享十元预算、简报并发槽、超时 UNKNOWN 与发布台合并保守结算。 */
@EnabledIfSystemProperty(named = "polyhouse.trend.integration", matches = "true")
class TrendBriefingLedgerIntegrationTest {
    private static final LocalDate ANCHOR = LocalDate.of(2026, 11, 23);
    private static final String FP_1 = "1".repeat(64);
    private static final String FP_2 = "2".repeat(64);
    private static final String PAYLOAD_1 = "{\"headline\":\"第一版风口简报有效载荷\"}";
    private static final String PAYLOAD_2 = "{\"headline\":\"第二版风口简报有效载荷\"}";

    private static Sandbox sandbox;
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager manager;
    private static TrendAiMapper aiMapper;
    private static TrendBriefingMapper briefingMapper;
    private static TrendFeeMapper feeMapper;
    private static TrendJobMapper jobMapper;
    private static TrendArchiveService archive;
    private static ValidatorFactory validation;

    private TrendOwnerAuthorization owners;
    private TrendAiLedger aiLedger;
    private TrendBriefingLedger briefingLedger;
    private TrendJobStore jobs;
    private TrendFeeService fees;

    @BeforeAll static void open() throws Exception {
        sandbox = new Sandbox();
        var source = new DriverManagerDataSource(sandbox.url, sandbox.user, sandbox.password);
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        var configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setArgNameBasedConstructorAutoMapping(true);
        for (var type : List.of(TrendAiMapper.class, TrendBriefingMapper.class, TrendFeeMapper.class,
                TrendJobMapper.class, TrendMapper.class)) {
            configuration.addMapper(type);
        }
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        var session = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        aiMapper = session.getMapper(TrendAiMapper.class);
        briefingMapper = session.getMapper(TrendBriefingMapper.class);
        feeMapper = session.getMapper(TrendFeeMapper.class);
        jobMapper = session.getMapper(TrendJobMapper.class);
        var reports = session.getMapper(TrendMapper.class);
        archive = new TrendArchiveService(reports, new TrendReportService(reports, TrendFixture.json(),
                new TrendProperties(true, null)), TrendFixture.json(), manager);
        validation = Validation.buildDefaultValidatorFactory();
    }

    @BeforeEach void clearOnlyOurTables() {
        jdbc.update("UPDATE tool_trend_job_slot SET active_id=NULL,generation=0,last_accepted_at=NULL");
        for (String table : List.of("tool_trend_briefing_resolution", "tool_trend_briefing_request", "tool_trend_briefing",
                "tool_trend_ai_resolution", "tool_trend_job", "tool_trend_daily", "tool_trend_report_version",
                "tool_trend_ai_request", "tool_trend_ai_repository", "tool_trend_ai_month")) {
            jdbc.update("DELETE FROM " + table);
        }
        owners = mock(TrendOwnerAuthorization.class);
        when(owners.lockSession("fixture")).thenReturn(TrendFixture.owner());
        when(owners.lockActor(42, 0)).thenReturn(TrendFixture.owner());
        aiLedger = new TrendAiLedger(aiMapper, TrendAiFixture.settings(), Clock.fixed(TrendAiFixture.NOW, ZoneOffset.UTC), manager);
        briefingLedger = new TrendBriefingLedger(briefingMapper, aiMapper, TrendAiFixture.settings(),
                Clock.fixed(TrendAiFixture.NOW, ZoneOffset.UTC), manager);
        jobs = new TrendJobStore(jobMapper, owners, archive, new TrendProperties(true, null),
                new TrendJobProperties(true), TrendAiFixture.settings(), manager);
        fees = new TrendFeeService(owners, feeMapper, jobMapper, jobs, aiLedger, briefingLedger,
                TrendAiFixture.settings(), validation.getValidator(), manager);
    }

    @AfterAll static void close() throws Exception {
        if (validation != null) validation.close();
        if (sandbox != null) sandbox.close();
    }

    @Test void sharesMonthlyBudgetWithSingleRepoLedgerAndRespectsSuspendSwitch() {
        // 将预算收紧为 5000 微元：单仓预留 2800 后仅剩 2200，简报再申请 2800 必须被 BUDGET_LIMIT 拦截。
        var tight = new TrendAiProperties(true, TrendAiFixture.KEY, "deepseek-flash", null, null, 5000L);
        var tightRepo = new TrendAiLedger(aiMapper, tight, Clock.fixed(TrendAiFixture.NOW, ZoneOffset.UTC), manager);
        var tightBriefing = new TrendBriefingLedger(briefingMapper, aiMapper, tight, Clock.fixed(TrendAiFixture.NOW, ZoneOffset.UTC), manager);

        String repoReq = UUID.randomUUID().toString();
        assertThat(tightRepo.reserve(repoReq, 1L, TrendAiFixture.SHA, 1000, 100, null).status()).isEqualTo("GRANTED");
        assertThat(tightBriefing.reserve(UUID.randomUUID().toString(), ANCHOR, FP_1, 1000, 100, null).status())
                .isEqualTo("BUDGET_LIMIT");

        // 单仓仅结算 1000 微元（退回 1800），剩余 4000 足以覆盖简报 2800 预留。
        tightRepo.settle(repoReq, 100, 100, null);
        String briefReq = UUID.randomUUID().toString();
        assertThat(tightBriefing.reserve(briefReq, ANCHOR, FP_1, 1000, 100, null).status()).isEqualTo("GRANTED");

        // 简报发生用量违约熔断当月后，单仓与另一锚点日简报新预留均被拒绝。
        tightBriefing.unknown(briefReq, true);
        assertThat(jdbc.queryForObject("SELECT suspended FROM tool_trend_ai_month WHERE month_key='2026-11'", Boolean.class))
                .isTrue();
        assertThat(tightRepo.reserve(UUID.randomUUID().toString(), 2L, TrendAiFixture.SHA, 100, 10, null).status())
                .isEqualTo("BUDGET_LIMIT");
        assertThat(tightBriefing.reserve(UUID.randomUUID().toString(), ANCHOR.minusDays(1), FP_2, 100, 10, null).status())
                .isEqualTo("BUDGET_LIMIT");
    }

    @Test void serializesConcurrentReservationsReusesSameFingerprintAndPreservesOldBriefingOnInvalidOutput() throws Exception {
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<TrendBriefingLedger.Decision>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return briefingLedger.reserve(UUID.randomUUID().toString(), ANCHOR, FP_1, 2000, 500, null);
            }));
        }
        ready.await();
        start.countDown();
        List<TrendBriefingLedger.Decision> decisions = new ArrayList<>();
        for (var f : futures) decisions.add(f.get(5, TimeUnit.SECONDS));
        pool.shutdownNow();

        var granted = decisions.stream().filter(d -> "GRANTED".equals(d.status())).toList();
        assertThat(granted).hasSize(1);
        assertThat(decisions.stream().filter(d -> "PENDING".equals(d.status())).count()).isEqualTo(threads - 1);

        // 校验通过结算：扣实际费用并落库有效简报（MySQL JSON 列会规范化空白，按语义字段核对）。
        String req1 = granted.getFirst().request().id();
        briefingLedger.settle(req1, 1000, 200, new TrendBriefingLedger.SettlementPayload(
                ANCHOR.minusDays(6), ANCHOR, 5, "SUFFICIENT", PAYLOAD_1, TrendAiFixture.NOW));
        var saved = briefingLedger.briefing(ANCHOR);
        assertThat(saved.activeRequest()).isNull();
        assertThat(TrendFixture.json().readTree(saved.payload()).path("headline").asText()).isEqualTo("第一版风口简报有效载荷");
        assertThat(saved.sourceFingerprint()).isEqualTo(FP_1);

        // 同指纹再次触发：零费用返回 REUSED，且不新增任何计费请求。
        var reused = briefingLedger.reserve(UUID.randomUUID().toString(), ANCHOR, FP_1, 2000, 500, FP_1);
        assertThat(reused.status()).isEqualTo("REUSED");
        assertThat(TrendFixture.json().readTree(reused.briefing().payload()).path("headline").asText()).isEqualTo("第一版风口简报有效载荷");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tool_trend_briefing_request", Long.class)).isEqualTo(1L);

        // 指纹变化后发起新请求，但模型输出校验失败（validPayload=null）：照实扣费且完整保留旧有效稿 PAYLOAD_1。
        String req2 = UUID.randomUUID().toString();
        assertThat(briefingLedger.reserve(req2, ANCHOR, FP_2, 2000, 500, FP_1).status()).isEqualTo("GRANTED");
        briefingLedger.settle(req2, 800, 100, null);
        var afterInvalid = briefingLedger.briefing(ANCHOR);
        assertThat(afterInvalid.activeRequest()).isNull();
        assertThat(TrendFixture.json().readTree(afterInvalid.payload()).path("headline").asText()).isEqualTo("第一版风口简报有效载荷");
        assertThat(afterInvalid.sourceFingerprint()).isEqualTo(FP_1);
        assertThat(jdbc.queryForObject("SELECT outcome FROM tool_trend_briefing_request WHERE id=?", String.class, req2))
                .isEqualTo("INVALID_OUTPUT");

        // 新指纹再次生成并成功校验后，平滑覆盖为 PAYLOAD_2。
        String req3 = UUID.randomUUID().toString();
        assertThat(briefingLedger.reserve(req3, ANCHOR, FP_2, 2000, 500, FP_1).status()).isEqualTo("GRANTED");
        briefingLedger.settle(req3, 900, 250, new TrendBriefingLedger.SettlementPayload(
                ANCHOR.minusDays(6), ANCHOR, 6, "SUFFICIENT", PAYLOAD_2, TrendAiFixture.NOW));
        assertThat(TrendFixture.json().readTree(briefingLedger.briefing(ANCHOR).payload()).path("headline").asText()).isEqualTo("第二版风口简报有效载荷");
        assertThat(briefingLedger.briefing(ANCHOR).sourceFingerprint()).isEqualTo(FP_2);
    }

    @Test void mergesPendingBriefingChargeIntoFeePanelAndSettlesConservativelyAfterFiveMinutes() throws Exception {
        // 先落一份有效旧稿，确保后续超时保守结算不会抹掉旧稿。
        String initial = UUID.randomUUID().toString();
        assertThat(briefingLedger.reserve(initial, ANCHOR, FP_1, 1000, 100, null).status()).isEqualTo("GRANTED");
        briefingLedger.settle(initial, 500, 100, new TrendBriefingLedger.SettlementPayload(
                ANCHOR.minusDays(6), ANCHOR, 4, "LIMITED", PAYLOAD_1, TrendAiFixture.NOW));
        long initialCharged = 500L * 2 + 100L * 8; // 1800 微元

        // 制造 1 笔单仓 UNKNOWN 与 1 笔简报 UNKNOWN（按固定 UUID 顺序验证合并分页）。
        String repoId = "10000000-0000-4000-8000-000000000001";
        String briefId = "20000000-0000-4000-8000-000000000002";
        assertThat(aiLedger.reserve(repoId, 101L, TrendAiFixture.SHA, 1000, 100, null).status()).isEqualTo("GRANTED");
        assertThat(briefingLedger.reserve(briefId, ANCHOR, FP_2, 1000, 100, FP_1).status()).isEqualTo("GRANTED");
        aiLedger.unknown(repoId, false);
        briefingLedger.unknown(briefId, false);

        // 发布台待核对页同时返回两笔，区分 chargeKind 且 repositoryId 保持非空兼容字符串。
        var page = fees.query(new TrendFeeQueryDTO("2026-11", null, 20), "fixture");
        assertThat(page.heldMicros()).isEqualTo(5600L);
        assertThat(page.pending()).hasSize(2);
        assertThat(page.pending().get(0).requestId()).isEqualTo(repoId);
        assertThat(page.pending().get(0).repositoryId()).isEqualTo("101");
        assertThat(page.pending().get(0).chargeKind()).isEqualTo("REPO");
        assertThat(page.pending().get(1).requestId()).isEqualTo(briefId);
        assertThat(page.pending().get(1).repositoryId()).isEqualTo("briefing:2026-11-23");
        assertThat(page.pending().get(1).chargeKind()).isEqualTo("BRIEFING");

        // 将预留时间对齐当前数据库 UTC 时间，验证不满 5 分钟保守结算必须拒绝。
        jdbc.update("UPDATE tool_trend_briefing_request SET created_at=UTC_TIMESTAMP(6) WHERE id=?", briefId);
        var briefResolveDto = new TrendFeeResolveDTO(UUID.randomUUID().toString(), briefId, 2800L, "COUNT_RESERVED", "RESULT_UNRECOVERABLE");
        assertThatThrownBy(() -> fees.resolve(briefResolveDto, "fixture"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));

        // 满 5 分钟后两笔均可经同一保守结算入口结清，审计分别入库且保守总额合并累加。
        jdbc.update("UPDATE tool_trend_briefing_request SET created_at=UTC_TIMESTAMP(6)-INTERVAL 6 MINUTE WHERE id=?", briefId);
        jdbc.update("UPDATE tool_trend_ai_request SET created_at=UTC_TIMESTAMP(6)-INTERVAL 6 MINUTE WHERE id=?", repoId);

        var briefResolved = fees.resolve(briefResolveDto, "fixture");
        assertThat(briefResolved.accountedMicros()).isEqualTo(2800L);
        assertThat(briefResolved.replayed()).isFalse();
        assertThat(fees.resolve(briefResolveDto, "fixture").replayed()).isTrue();
        assertThat(fees.confirm(new TrendFeeResolutionQueryDTO(briefResolveDto.resolutionId()), "fixture").accountedMicros())
                .isEqualTo(2800L);

        // 简报槽位已释放且原有效旧稿 PAYLOAD_1 完整保留。
        var afterClose = briefingLedger.briefing(ANCHOR);
        assertThat(afterClose.activeRequest()).isNull();
        assertThat(TrendFixture.json().readTree(afterClose.payload()).path("headline").asText()).isEqualTo("第一版风口简报有效载荷");

        var repoResolveDto = new TrendFeeResolveDTO(UUID.randomUUID().toString(), repoId, 2800L, "COUNT_RESERVED", "PROVIDER_RECORD_CHECKED");
        fees.resolve(repoResolveDto, "fixture");

        var summary = fees.query(new TrendFeeQueryDTO("2026-11", null, 20), "fixture");
        assertThat(summary.pending()).isEmpty();
        assertThat(summary.heldMicros()).isZero();
        assertThat(summary.accountedMicros()).isEqualTo(initialCharged + 2800L + 2800L);
        assertThat(summary.conservativeMicros()).isEqualTo(5600L);
    }

    @Test void enforces015ConstraintsAndSupportsCleanReverseRollbackWithoutLosingMonthFacts() throws Exception {
        // 验证 CHECK 约束拦截非法 covered_days（<3）与非法 sample_status。
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO tool_trend_briefing(anchor_date,window_start,window_end,covered_days,sample_status,
                source_fingerprint,model,rule_version,payload,generated_at)
                VALUES('2026-11-23','2026-11-17','2026-11-23',2,'LIMITED',?,'deepseek-flash','briefing-1','{}',UTC_TIMESTAMP(6))
                """, FP_1)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO tool_trend_briefing(anchor_date,window_start,window_end,covered_days,sample_status,
                source_fingerprint,model,rule_version,payload,generated_at)
                VALUES('2026-11-23','2026-11-17','2026-11-23',4,'INSUFFICIENT',?,'deepseek-flash','briefing-1','{}',UTC_TIMESTAMP(6))
                """, FP_1)).isInstanceOf(DataAccessException.class);

        // 写入一笔正常结算后再按注释逆序回退 015，共享月表已用费用必须保留，随后重跑 015 可干净恢复。
        String req = UUID.randomUUID().toString();
        assertThat(briefingLedger.reserve(req, ANCHOR, FP_1, 1000, 100, null).status()).isEqualTo("GRANTED");
        briefingLedger.settle(req, 300, 50, new TrendBriefingLedger.SettlementPayload(
                ANCHOR.minusDays(6), ANCHOR, 3, "LIMITED", PAYLOAD_1, TrendAiFixture.NOW));
        long usedBeforeRollback = jdbc.queryForObject("SELECT used_micros FROM tool_trend_ai_month WHERE month_key='2026-11'", Long.class);
        assertThat(usedBeforeRollback).isEqualTo(1000L);

        jdbc.execute("DROP TABLE tool_trend_briefing_resolution");
        jdbc.execute("DROP TABLE tool_trend_briefing_request");
        jdbc.execute("DROP TABLE tool_trend_briefing");
        assertThat(jdbc.queryForObject("SELECT used_micros FROM tool_trend_ai_month WHERE month_key='2026-11'", Long.class))
                .isEqualTo(usedBeforeRollback);

        try (var connection = DriverManager.getConnection(sandbox.url, sandbox.user, sandbox.password)) {
            migrate(connection, "015_trend_briefings.sql");
        }
        assertThat(briefingLedger.briefing(ANCHOR)).isNull();
    }

    private static void migrate(Connection connection, String file) {
        ScriptUtils.executeSqlScript(connection, new EncodedResource(new FileSystemResource("sql/" + file), StandardCharsets.UTF_8));
    }

    private static final class Sandbox implements AutoCloseable {
        private final String name = "polyhouse_trend_brief_" + UUID.randomUUID().toString().replace("-", "");
        private final String user = System.getenv("DB_USERNAME");
        private final String password = System.getenv("DB_PASSWORD");
        private final String direct;
        private final String url;

        private Sandbox() throws Exception {
            String configured = System.getenv().getOrDefault("DB_URL", "jdbc:mysql://localhost:3306/polyhouse_db");
            if (!configured.startsWith("jdbc:mysql://")) throw new IllegalStateException("Only local MySQL is allowed");
            URI parsed = URI.create(configured.substring(5));
            if (!List.of("localhost", "127.0.0.1").contains(parsed.getHost()) || !"/polyhouse_db".equals(parsed.getPath())
                    || parsed.getUserInfo() != null || user == null || password == null) {
                throw new IllegalStateException("Invalid local sandbox configuration");
            }
            direct = "jdbc:mysql://" + parsed.getHost() + ":" + (parsed.getPort() < 0 ? 3306 : parsed.getPort())
                    + "/?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=3000&socketTimeout=5000&serverTimezone=Asia/Shanghai";
            url = direct.replace("/?", "/" + name + "?");
            try (var connection = DriverManager.getConnection(direct, user, password); var command = connection.createStatement()) {
                command.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4");
                connection.setCatalog(name);
                try {
                    for (String file : List.of("010_trend_reports.sql", "011_trend_ai.sql", "012_trend_jobs.sql",
                            "013_trend_ai_resolution.sql", "015_trend_briefings.sql")) {
                        migrate(connection, file);
                    }
                } catch (RuntimeException failure) {
                    try { close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                    throw failure;
                }
            }
        }

        @Override public void close() throws Exception {
            if (!name.matches("polyhouse_trend_brief_[0-9a-f]{32}")) throw new IllegalStateException("Invalid sandbox name");
            try (var connection = DriverManager.getConnection(direct, user, password); var command = connection.createStatement()) {
                command.execute("DROP DATABASE IF EXISTS `" + name + "`");
            }
        }
    }
}
