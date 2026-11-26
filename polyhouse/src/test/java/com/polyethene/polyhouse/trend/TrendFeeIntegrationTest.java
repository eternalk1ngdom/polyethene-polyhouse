package com.polyethene.polyhouse.trend;

import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.config.*;
import com.polyethene.polyhouse.mapper.*;
import com.polyethene.polyhouse.model.dto.*;
import com.polyethene.polyhouse.model.vo.TrendFeeResolutionVO;
import com.polyethene.polyhouse.service.*;
import com.polyethene.polyhouse.service.trend.*;
import jakarta.validation.*;
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

/** 随机八表MySQL验证费用守恒、审计回滚和晚到响应竞态，不创建账号或连接付费平台。 */
@EnabledIfSystemProperty(named = "polyhouse.trend.integration", matches = "true")
class TrendFeeIntegrationTest {
    private static Sandbox sandbox;
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager manager;
    private static TrendAiMapper aiMapper;
    private static TrendBriefingMapper briefingMapper;
    private static TrendFeeMapper feeMapper;
    private static TrendJobMapper jobMapper;
    private static TrendArchiveService archive;
    private static ValidatorFactory validation;
    private TrendAiLedger ledger;
    private TrendBriefingLedger briefingLedger;
    private TrendJobStore jobs;
    private TrendFeeService fees;
    private TrendOwnerAuthorization owners;

    @BeforeAll static void open() throws Exception {
        sandbox = new Sandbox();
        var source = new DriverManagerDataSource(sandbox.url, sandbox.user, sandbox.password);
        jdbc = new JdbcTemplate(source); manager = new DataSourceTransactionManager(source);
        var configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true); configuration.setArgNameBasedConstructorAutoMapping(true);
        for (var type : List.of(TrendAiMapper.class, TrendBriefingMapper.class, TrendFeeMapper.class, TrendJobMapper.class, TrendMapper.class)) {
            configuration.addMapper(type);
        }
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(configuration);
        var session = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        aiMapper = session.getMapper(TrendAiMapper.class); briefingMapper = session.getMapper(TrendBriefingMapper.class);
        feeMapper = session.getMapper(TrendFeeMapper.class); jobMapper = session.getMapper(TrendJobMapper.class);
        var reports = session.getMapper(TrendMapper.class);
        archive = new TrendArchiveService(reports, new TrendReportService(reports, TrendFixture.json(), new TrendProperties(true, null)), TrendFixture.json(), manager);
        validation = Validation.buildDefaultValidatorFactory();
    }
    @BeforeEach void clearOnlyOurTables() {
        jdbc.update("UPDATE tool_trend_job_slot SET active_id=NULL,generation=0,last_accepted_at=NULL");
        for (String table : List.of("tool_trend_briefing_resolution", "tool_trend_briefing_request", "tool_trend_briefing",
                "tool_trend_ai_resolution", "tool_trend_job", "tool_trend_daily", "tool_trend_report_version",
                "tool_trend_ai_request", "tool_trend_ai_repository", "tool_trend_ai_month")) {
            // 测试内部固定表白名单，没有任何用户输入，也只连接本类创建的随机数据库。
            jdbc.update("DELETE FROM " + table);
        }
        owners = mock(TrendOwnerAuthorization.class);
        when(owners.lockSession("fixture")).thenReturn(TrendFixture.owner());
        when(owners.lockActor(42, 0)).thenReturn(TrendFixture.owner());
        ledger = new TrendAiLedger(aiMapper, TrendAiFixture.settings(), Clock.fixed(TrendAiFixture.NOW, ZoneOffset.UTC), manager);
        briefingLedger = new TrendBriefingLedger(briefingMapper, aiMapper, TrendAiFixture.settings(),
                Clock.fixed(TrendAiFixture.NOW, ZoneOffset.UTC), manager);
        jobs = new TrendJobStore(jobMapper, owners, archive, new TrendProperties(true, null), new TrendJobProperties(true), TrendAiFixture.settings(), manager);
        fees = service(TrendAiFixture.settings());
    }
    @AfterAll static void close() throws Exception {
        if (validation != null) validation.close();
        if (sandbox != null) sandbox.close();
    }

    @Test void emptyQueryDoesNotCreateMonthAndDefaultUsesDatabaseBeijingDate() {
        var result = fees.query(new TrendFeeQueryDTO(null, null, null), "fixture");
        assertThat(result.month()).isEqualTo(YearMonth.from(jobMapper.now().toInstant(ZoneOffset.UTC).atZone(ZoneId.of("Asia/Shanghai"))).toString());
        assertThat(result.accountedMicros()).isZero(); assertThat(result.heldMicros()).isZero();
        assertThat(result.remainingMicros()).isEqualTo(10_000_000); assertThat(result.pending()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tool_trend_ai_month", Long.class)).isZero();
    }

    @Test void pendingPageIsBoundedKeysetAndReducedConfiguredLimitNeverMakesNegativeRemaining() {
        List<String> ids = List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString()).stream().sorted().toList();
        for (int index = 0; index < ids.size(); index++) reserve(ids.get(index), index + 1);
        ledger.unknown(ids.getFirst(), false);
        var page = fees.query(new TrendFeeQueryDTO("2026-11", null, 2), "fixture");
        assertThat(page.pending()).extracting(item -> item.requestId()).containsExactly(ids.get(0), ids.get(1));
        assertThat(page.nextAfter()).isEqualTo(ids.get(1)); assertThat(page.heldMicros()).isEqualTo(8400);
        var next = fees.query(new TrendFeeQueryDTO("2026-11", page.nextAfter(), 2), "fixture");
        assertThat(next.pending()).extracting(item -> item.requestId()).containsExactly(ids.get(2)); assertThat(next.nextAfter()).isNull();
        var lower = service(new TrendAiProperties(false, null, null, null, null, 5000L)).query(new TrendFeeQueryDTO("2026-11", null, null), "fixture");
        assertThat(lower.remainingMicros()).isZero(); assertThat(number("used_micros", "2026-11")).isZero();
    }

    @Test void closingUnknownTransfersWholeReservationToUsedWithoutLosingCachedInsight() {
        String id = reserve(); ledger.unknown(id, false); age(id);
        String cached = new TrendInsightCodec(TrendFixture.json()).encode(TrendAiFixture.insight(TrendAiFixture.NOW));
        jdbc.update("UPDATE tool_trend_ai_repository SET payload=? WHERE repository_id=1", cached);
        String stored = aiMapper.repository(1).payload();
        var result = fees.resolve(request(id), "fixture");
        assertThat(result.accountedMicros()).isEqualTo(2800); assertThat(result.replayed()).isFalse();
        assertClosed(id, 2800); assertThat(aiMapper.repository(1).payload()).isEqualTo(stored);
        assertThat(jdbc.queryForObject("SELECT prompt_tokens FROM tool_trend_ai_request WHERE id=?", Integer.class, id)).isNull();
        assertThat(jdbc.queryForObject("SELECT outcome FROM tool_trend_ai_request WHERE id=?", String.class, id)).isEqualTo("MANUAL_RESERVED");
        var summary = fees.query(new TrendFeeQueryDTO("2026-11", null, null), "fixture");
        assertThat(summary.conservativeMicros()).isEqualTo(2800); assertThat(summary.pending()).isEmpty();
        assertThat(ledger.reserve(UUID.randomUUID().toString(), 1, TrendAiFixture.SHA, 1000, 100, stored).status()).isEqualTo("GRANTED");
    }

    @Test void replayPreservesFactEvenWhenAnotherJobIsQueuedAndChangedIntentIsRejected() {
        String id = reserve(); age(id); var request = request(id); var original = fees.resolve(request, "fixture");
        jobs.submit(UUID.randomUUID().toString(), TrendFixture.owner());
        var replay = fees.resolve(request, "fixture"); assertThat(replay.replayed()).isTrue();
        assertThat(replay.completedAt()).isEqualTo(original.completedAt());
        assertThat(fees.confirm(new TrendFeeResolutionQueryDTO(request.resolutionId()), "fixture").completedAt()).isEqualTo(original.completedAt());
        conflict(() -> fees.resolve(new TrendFeeResolveDTO(request.resolutionId(), id, 2800L, "COUNT_RESERVED", "PROVIDER_RECORD_CHECKED"), "fixture"));
        conflict(() -> fees.resolve(new TrendFeeResolveDTO(request.resolutionId(), UUID.randomUUID().toString(), 2800L, "COUNT_RESERVED", request.reason()), "fixture"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tool_trend_ai_resolution", Long.class)).isEqualTo(1);
        assertClosed(id, 2800);
    }

    @Test void differentResolutionCannotCloseAlreadyResolvedOrNormallySettledRequest() {
        String id = reserve(); age(id); fees.resolve(request(id), "fixture");
        conflict(() -> fees.resolve(request(id), "fixture"));
        String normal = UUID.randomUUID().toString(); reserve(normal, 2); age(normal); ledger.settle(normal, 100, 20, null);
        conflict(() -> fees.resolve(request(normal), "fixture"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tool_trend_ai_resolution", Long.class)).isEqualTo(1);
        assertThat(number("used_micros", "2026-11")).isEqualTo(3160);
    }

    @Test void recentReservationAndMismatchedAmountCannotBeClosed() {
        String id = reserve(); jdbc.update("UPDATE tool_trend_ai_request SET created_at=UTC_TIMESTAMP(6) WHERE id=?", id);
        conflict(() -> fees.resolve(request(id), "fixture")); age(id);
        conflict(() -> fees.resolve(new TrendFeeResolveDTO(UUID.randomUUID().toString(), id, 2799L, "COUNT_RESERVED", "RESULT_UNRECOVERABLE"), "fixture"));
        assertThat(aiMapper.request(id).state()).isEqualTo("RESERVED"); assertThat(number("held_micros", "2026-11")).isEqualTo(2800);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tool_trend_ai_resolution", Long.class)).isZero();
    }

    @Test void activeQueuedOrRunningJobPreventsClosingButExpiredGenerationCanBeFenced() {
        String id = reserve(); age(id); String task = UUID.randomUUID().toString(); jobs.submit(task, TrendFixture.owner());
        conflict(() -> fees.resolve(request(id), "fixture"));
        var running = jobs.claim(); conflict(() -> fees.resolve(request(id), "fixture"));
        jdbc.update("UPDATE tool_trend_job SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?", task);
        fees.resolve(request(id), "fixture");
        assertThat(jobs.query(task).state()).isEqualTo("INTERRUPTED");
        assertThatThrownBy(() -> jobs.guard(running)).isInstanceOf(TrendExecutionLost.class);
        assertThatThrownBy(jobs::lockIdleForFeeResolution).isInstanceOf(IllegalStateException.class);
        assertClosed(id, 2800);
    }

    @Test void existingPauseAndLateUsageViolationCannotBeClearedByManualClosure() {
        String id = reserve(); age(id); ledger.unknown(id, false); ledger.unknown(id, true);
        fees.resolve(request(id), "fixture"); assertThat(aiMapper.lockMonth("2026-11").suspended()).isTrue();
        assertThat(ledger.reserve(UUID.randomUUID().toString(), 2, TrendAiFixture.SHA, 1000, 100, null).status()).isEqualTo("BUDGET_LIMIT");
    }

    @Test void lateResponseDoesNotOverwriteClosureButLateOutOfBoundsStillSuspendsOriginalMonth() {
        String id = reserve(); age(id); fees.resolve(request(id), "fixture");
        ledger.settle(id, 100, 20, new TrendInsightCodec(TrendFixture.json()).encode(TrendAiFixture.insight(TrendAiFixture.NOW)));
        ledger.cancelBeforeSend(id);
        assertClosed(id, 2800); assertThat(aiMapper.repository(1).payload()).isNull();
        ledger.unknown(id, true);
        assertThat(fees.query(new TrendFeeQueryDTO("2026-11", null, null), "fixture").suspended()).isTrue();
    }

    @Test void crossMonthClosureStaysInOriginalReservationMonth() {
        var previousLedger = new TrendAiLedger(aiMapper, TrendAiFixture.settings(), Clock.fixed(Instant.parse("2026-10-31T15:59:00Z"), ZoneOffset.UTC), manager);
        String id = UUID.randomUUID().toString(); assertThat(previousLedger.reserve(id, 1, TrendAiFixture.SHA, 1000, 100, null).status()).isEqualTo("GRANTED"); age(id);
        assertThat(fees.resolve(request(id), "fixture").month()).isEqualTo("2026-10");
        assertThat(number("used_micros", "2026-10")).isEqualTo(2800);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tool_trend_ai_month WHERE month_key='2026-11'", Long.class)).isZero();
    }

    @Test void concurrentNormalSettlementAndClosureChargeExactlyOneOutcome() throws Exception {
        String id = reserve(); age(id); var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var closing = workers.submit(() -> {
                start.await();
                try { fees.resolve(request(id), "fixture"); return true; }
                catch (BusinessException failure) { assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.CONFLICT); return false; }
            });
            var known = workers.submit(() -> { start.await(); ledger.settle(id, 100, 20, null); return true; });
            start.countDown(); boolean closed = closing.get(10, TimeUnit.SECONDS); assertThat(known.get(10, TimeUnit.SECONDS)).isTrue();
            assertClosed(id, closed ? 2800 : 360);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tool_trend_ai_resolution", Long.class)).isEqualTo(closed ? 1 : 0);
        }
    }

    @Test void concurrentClosuresHaveOneAuditAndNoDoubleSettlement() throws Exception {
        String id = reserve(); age(id); var start = new CountDownLatch(1); var request = request(id);
        try (var workers = Executors.newFixedThreadPool(2)) {
            Callable<TrendFeeResolutionVO> call = () -> { start.await(); return fees.resolve(request, "fixture"); };
            var a = workers.submit(call); var b = workers.submit(call); start.countDown();
            var first = a.get(10, TimeUnit.SECONDS); var second = b.get(10, TimeUnit.SECONDS);
            assertThat(first.replayed()).isNotEqualTo(second.replayed()); assertThat(first.completedAt()).isEqualTo(second.completedAt());
        }
        assertClosed(id, 2800); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tool_trend_ai_resolution", Long.class)).isEqualTo(1);
    }

    @Test void auditFailureRollsBackAllFeeAndRepositoryChanges() {
        String id = reserve(); ledger.unknown(id, false); age(id);
        jdbc.execute("ALTER TABLE tool_trend_ai_resolution ADD CONSTRAINT ck_fixture_audit CHECK (accounted_micros<>2800)");
        try {
            assertThatThrownBy(() -> fees.resolve(request(id), "fixture"))
                    .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
            assertThat(aiMapper.request(id).state()).isEqualTo("UNKNOWN"); assertThat(aiMapper.repository(1).activeRequest()).isEqualTo(id);
            assertThat(number("held_micros", "2026-11")).isEqualTo(2800); assertThat(number("used_micros", "2026-11")).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tool_trend_ai_resolution", Long.class)).isZero();
        } finally { jdbc.execute("ALTER TABLE tool_trend_ai_resolution DROP CHECK ck_fixture_audit"); }
        fees.resolve(request(id), "fixture"); assertClosed(id, 2800);
    }

    @Test void closureAtTenYuanBoundaryDoesNotRestoreAvailableBudget() {
        String id = reserve(); age(id); jdbc.update("UPDATE tool_trend_ai_month SET used_micros=9997200 WHERE month_key='2026-11'");
        fees.resolve(request(id), "fixture");
        assertThat(number("used_micros", "2026-11")).isEqualTo(10_000_000); assertThat(number("held_micros", "2026-11")).isZero();
        assertThat(fees.query(new TrendFeeQueryDTO("2026-11", null, null), "fixture").remainingMicros()).isZero();
        assertThat(ledger.reserve(UUID.randomUUID().toString(), 2, TrendAiFixture.SHA, 1000, 100, null).status()).isEqualTo("BUDGET_LIMIT");
    }

    @Test void migrationRollbackPreservesAccountedFeeAndAuditCanBeRestoredWithoutRecharging() throws Exception {
        String id = reserve(); age(id); var request = request(id); fees.resolve(request, "fixture");
        var backup = feeMapper.resolution(request.resolutionId());
        assertThatThrownBy(() -> jdbc.update("UPDATE tool_trend_ai_resolution SET accounted_micros=0"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        jdbc.execute("DROP TABLE tool_trend_ai_resolution");
        assertClosed(id, 2800); ledger.settle(id, 100, 20, null); assertClosed(id, 2800);
        try (var connection = DriverManager.getConnection(sandbox.url, sandbox.user, sandbox.password)) { migrate(connection, "013_trend_ai_resolution.sql"); }
        assertThat(feeMapper.insert(backup)).isEqualTo(1);
        assertThat(fees.resolve(request, "fixture").replayed()).isTrue();
        assertThat(fees.query(new TrendFeeQueryDTO("2026-11", null, null), "fixture").conservativeMicros()).isEqualTo(2800);
    }

    @Test void migration017DegradesSafelyBeforeApplyComputesDualBookAfterApplyAndRollsBackCleanly() throws Exception {
        // 1. 017 未迁移前：单仓与简报带缓存命中拆分结算不得污染主事务，usage 安全降级为 null。
        String preRepo = UUID.randomUUID().toString();
        assertThat(ledger.reserve(preRepo, 10L, TrendAiFixture.SHA, 1000, 100, null).status()).isEqualTo("GRANTED");
        ledger.settle(preRepo, 500, 100, 300, 200, null);

        LocalDate anchor1 = LocalDate.of(2026, 11, 20);
        String preBrief = UUID.randomUUID().toString();
        assertThat(briefingLedger.reserve(preBrief, anchor1, "1".repeat(64), 1000, 100, null).status()).isEqualTo("GRANTED");
        briefingLedger.settle(preBrief, 400, 50, 200, 200, new TrendBriefingLedger.SettlementPayload(
                anchor1.minusDays(6), anchor1, 4, "LIMITED", "{\"headline\":\"未迁移017样本\"}", TrendAiFixture.NOW));

        // 500*2 + 100*8 = 1800; 400*2 + 50*8 = 1200; 合计 3000 微元。
        assertThat(number("used_micros", "2026-11")).isEqualTo(3000L);
        assertThat(number("held_micros", "2026-11")).isZero();
        var before017 = fees.query(new TrendFeeQueryDTO("2026-11", null, null), "fixture");
        assertThat(before017.accountedMicros()).isEqualTo(3000L);
        assertThat(before017.usage()).isNull();

        // 2. 执行 017 正向迁移，验证 CHECK 约束、单仓+简报+旧行双口径汇总，并在 finally 逆序回退以保持同库基线。
        try (var connection = DriverManager.getConnection(sandbox.url, sandbox.user, sandbox.password)) {
            migrate(connection, "017_trend_ai_cache_tokens.sql");
        }
        try {
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE tool_trend_ai_request SET cache_hit_tokens=-1 WHERE id=?", preRepo))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE tool_trend_briefing_request SET estimated_actual_micros=-1 WHERE id=?", preBrief))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);

            // TrendAiFixture.NOW = 2026-11-16T00:00:00Z = 北京时间 2026-11-16 08:00（周一 00:30～08:30 非高峰 5 折，缓存命中 0.02x）：
            // 单仓新结算：1000 输入（700 命中 + 300 未命中）+ 200 输出；保守=1000*2+200*8=3600；
            // 估算实扣=ceil(700*2*0.02*0.5 + 300*2*0.5 + 200*8*0.5)=14+300+800=1114。
            String postRepo = UUID.randomUUID().toString();
            assertThat(ledger.reserve(postRepo, 11L, TrendAiFixture.SHA, 1200, 300, null).status()).isEqualTo("GRANTED");
            ledger.settle(postRepo, 1000, 200, 700, 300, null);

            // 简报新结算：800 输入（600 命中 + 200 未命中）+ 100 输出；保守=800*2+100*8=2400；
            // 估算实扣=ceil(600*2*0.02*0.5 + 200*2*0.5 + 100*8*0.5)=12+200+400=612。
            LocalDate anchor2 = LocalDate.of(2026, 11, 21);
            String postBrief = UUID.randomUUID().toString();
            assertThat(briefingLedger.reserve(postBrief, anchor2, "2".repeat(64), 1000, 200, null).status()).isEqualTo("GRANTED");
            briefingLedger.settle(postBrief, 800, 100, 600, 200, new TrendBriefingLedger.SettlementPayload(
                    anchor2.minusDays(6), anchor2, 5, "SUFFICIENT", "{\"headline\":\"迁移017后样本\"}", TrendAiFixture.NOW));

            var after017 = fees.query(new TrendFeeQueryDTO("2026-11", null, null), "fixture");
            // 保守硬闸门总计 = 3000 + 3600 + 2400 = 9000 微元。
            assertThat(after017.accountedMicros()).isEqualTo(9000L);
            assertThat(after017.usage()).isNotNull();
            assertThat(after017.usage().settledRequests()).isEqualTo(4L);
            assertThat(after017.usage().promptTokens()).isEqualTo(500L + 400L + 1000L + 800L);
            assertThat(after017.usage().cacheHitTokens()).isEqualTo(700L + 600L);
            // 旧行 NULL 命中列回退为全部未命中：500 + 400 + 300 + 200 = 1400。
            assertThat(after017.usage().cacheMissTokens()).isEqualTo(1400L);
            assertThat(after017.usage().completionTokens()).isEqualTo(100L + 50L + 200L + 100L);
            // 预计平台实扣 = 旧行回退保守额 3000 + 新行空闲 5 折估算 1114 + 612 = 4726 微元。
            assertThat(after017.usage().estimatedActualMicros()).isEqualTo(4726L);
        } finally {
            // 3. 逆序回退 017：先删 CHECK 约束再删三列，确认既有主费用与请求事实完整无损且降级回 usage=null。
            jdbc.execute("ALTER TABLE tool_trend_briefing_request DROP CHECK chk_trend_briefing_req_cache_tokens, "
                    + "DROP COLUMN estimated_actual_micros, DROP COLUMN cache_miss_tokens, DROP COLUMN cache_hit_tokens");
            jdbc.execute("ALTER TABLE tool_trend_ai_request DROP CHECK chk_trend_ai_req_cache_tokens, "
                    + "DROP COLUMN estimated_actual_micros, DROP COLUMN cache_miss_tokens, DROP COLUMN cache_hit_tokens");
        }

        var afterRollback = fees.query(new TrendFeeQueryDTO("2026-11", null, null), "fixture");
        assertThat(afterRollback.accountedMicros()).isEqualTo(9000L);
        assertThat(afterRollback.usage()).isNull();
    }

    private TrendFeeService service(TrendAiProperties settings) {
        return new TrendFeeService(owners, feeMapper, jobMapper, jobs, ledger, settings, validation.getValidator(), manager);
    }
    private String reserve() { String id = UUID.randomUUID().toString(); reserve(id, 1); return id; }
    private void reserve(String id, long repo) { assertThat(ledger.reserve(id, repo, TrendAiFixture.SHA, 1000, 100, null).status()).isEqualTo("GRANTED"); }
    private static void age(String id) { jdbc.update("UPDATE tool_trend_ai_request SET created_at=UTC_TIMESTAMP(6)-INTERVAL 6 MINUTE WHERE id=?", id); }
    private static TrendFeeResolveDTO request(String id) { return new TrendFeeResolveDTO(UUID.randomUUID().toString(), id, 2800L, "COUNT_RESERVED", "RESULT_UNRECOVERABLE"); }
    private static void conflict(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
    }
    private static void assertClosed(String id, long expected) {
        assertThat(aiMapper.request(id).state()).isEqualTo("SETTLED"); assertThat(aiMapper.request(id).chargedMicros()).isEqualTo(expected);
        assertThat(aiMapper.repository(aiMapper.request(id).repositoryId()).activeRequest()).isNull();
        assertThat(number("held_micros", aiMapper.request(id).monthKey())).isZero();
        assertThat(number("used_micros", aiMapper.request(id).monthKey())).isEqualTo(expected);
    }
    private static long number(String field, String month) {
        if (!Set.of("used_micros", "held_micros").contains(field)) throw new IllegalArgumentException("Unknown fixed test field");
        return jdbc.queryForObject("SELECT " + field + " FROM tool_trend_ai_month WHERE month_key=?", Long.class, month);
    }
    private static void migrate(Connection connection, String file) {
        ScriptUtils.executeSqlScript(connection, new EncodedResource(new FileSystemResource("sql/" + file), StandardCharsets.UTF_8));
    }
    /** 只允许本机配置，随机库名创建/清理前严格核验；不在开发库初始化OWNER或模块表。 */
    private static final class Sandbox implements AutoCloseable {
        private final String name = "polyhouse_trend_fee_" + UUID.randomUUID().toString().replace("-", "");
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
                try {
                    for (String file : List.of("010_trend_reports.sql", "011_trend_ai.sql", "012_trend_jobs.sql",
                            "013_trend_ai_resolution.sql", "015_trend_briefings.sql")) migrate(connection, file);
                } catch (RuntimeException failure) { try { close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); } throw failure; }
            }
        }
        @Override public void close() throws Exception {
            if (!name.matches("polyhouse_trend_fee_[0-9a-f]{32}")) throw new IllegalStateException("Invalid sandbox name");
            try (var connection = DriverManager.getConnection(direct, user, password); var command = connection.createStatement()) {
                command.execute("DROP DATABASE IF EXISTS `" + name + "`");
            }
        }
    }
}
