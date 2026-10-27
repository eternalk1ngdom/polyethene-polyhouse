package com.polyethene.polyhouse.service;

import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.constant.RedisConstants;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.mapper.OwnerBootstrapMapper;
import com.polyethene.polyhouse.model.dto.LoginDTO;
import com.polyethene.polyhouse.model.dto.RegisterDTO;
import com.polyethene.polyhouse.ops.OwnerBootstrapCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/** 真实提交、跨连接竞争和独立 CLI 进程验收；仅用于未初始化真实屋主的本机专用开发库。 */
@EnabledIfSystemProperty(named = "polyhouse.auth.integration", matches = "true")
@SpringBootTest
@Import(OwnerBootstrapService.class)
class OwnerBootstrapIntegrationTest {
    @Autowired private OwnerBootstrapService owners;
    @Autowired private OwnerBootstrapMapper mapper;
    @Autowired private AuthService auth;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AuthTransactionFixtureMapper fixtures;
    @Autowired private StringRedisTemplate redis;
    @Autowired private PlatformTransactionManager transactions;
    @SpyBean private AuthRedisStore sessions;
    private final List<Fixture> owned = new ArrayList<>();

    @BeforeEach
    void requireUnclaimedDedicatedLocalDatabase() {
        // 不能为了测试移除真实屋主；生产/已正式初始化的数据库应使用独立测试库而非运行此套件。
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE owner_slot = 1", Integer.class))
                .as("本套件仅允许没有真实屋主的专用开发库").isZero();
        assertThat(mapper.record().userId()).as("不得覆盖真实初始化记录").isNull();
    }

    @AfterEach
    void cleanupOnlyRegisteredFixturesAndTheirExactBootstrapRecord() {
        reset(sessions);
        List<Throwable> failures = new ArrayList<>();
        for (Fixture fixture : owned) {
            try {
                try {
                    redis.delete(keys(fixture.id()));
                } finally {
                    // 测试允许清理自己创建的授权记录，必须同时匹配 ID 和随机账号；实际命令没有重置功能。
                    jdbc.update("UPDATE sys_owner_bootstrap SET user_id=NULL, username=NULL, previous_role=NULL, "
                                    + "operator_name=NULL, initialized_at=NULL WHERE slot=1 AND user_id=? AND username=?",
                            fixture.id(), fixture.username());
                    assertThat(fixtures.removeOwnedFixture(fixture.id(), fixture.username())).isEqualTo(1);
                    assertThat(fixtures.deleted(fixture.id(), fixture.username())).isNull();
                }
            } catch (RuntimeException | AssertionError error) {
                failures.add(error);
            }
        }
        assertThat(failures).as("精确清理本套件夹具和初始化记录").isEmpty();
    }

    @Test
    void shouldPreviewThenPromoteOnceRevokeAllClientsAndKeepUnrelatedUserOnline() {
        Fixture target = register();
        Fixture other = register();
        String pc = login(target, "PC");
        String h5 = login(target, "H5");
        String plugin = login(target, "PLUGIN");
        String unrelated = login(other, "PC");
        assertThat(owners.inspect(target.id(), target.username()).role()).isEqualTo(UserRoleEnum.USER);
        assertThat(mapper.record().userId()).isNull();
        assertThat(redis.countExistingKeys(keys(target.id()))).isEqualTo(3);
        assertThat(owners.initialize(target.id(), target.username(), "first-operator")).isTrue();
        assertThat(mapper.target(target.id()).role()).isEqualTo(UserRoleEnum.OWNER);
        assertThat(redis.countExistingKeys(keys(target.id()))).isZero();
        assertThat(jdbc.queryForObject("SELECT experience FROM sys_user WHERE id=?", Long.class, target.id())).isEqualTo(10L);
        assertThat(jdbc.queryForObject("SELECT coins FROM sys_user WHERE id=?", Long.class, target.id())).isEqualTo(1L);
        for (String old : List.of(pc, h5, plugin)) assertCode(() -> auth.getCurrentUser(old), ErrorCode.UNAUTHORIZED);
        assertThat(auth.getCurrentUser(unrelated).id()).isEqualTo(Long.toString(other.id()));
        String ownerToken = login(target, "PC");
        assertCode(() -> auth.deregister(ownerToken), ErrorCode.FORBIDDEN);
        assertThat(fixtures.deleted(target.id(), target.username())).isZero();
        assertThat(auth.getCurrentUser(ownerToken).role()).isEqualTo(UserRoleEnum.OWNER);
        Object firstTime = jdbc.queryForObject("SELECT initialized_at FROM sys_owner_bootstrap WHERE slot=1", Object.class);
        assertThat(owners.initialize(target.id(), target.username(), "repeat-operator")).isFalse();
        assertThat(jdbc.queryForObject("SELECT operator_name FROM sys_owner_bootstrap WHERE slot=1", String.class)).isEqualTo("first-operator");
        assertThat(jdbc.queryForObject("SELECT initialized_at FROM sys_owner_bootstrap WHERE slot=1", Object.class)).isEqualTo(firstTime);
        assertThat(redis.countExistingKeys(keys(target.id()))).isZero();
    }

    @Test
    void shouldRejectWrongIdentityDisabledAndDeletedTargetsWithoutRevocation() {
        Fixture target = register();
        String token = login(target, "PC");
        assertCode(() -> owners.initialize(target.id(), "different_account", "operator"), ErrorCode.INVALID_PARAMETER);
        jdbc.update("UPDATE sys_user SET status=0 WHERE id=?", target.id());
        assertCode(() -> owners.initialize(target.id(), target.username(), "operator"), ErrorCode.CONFLICT);
        jdbc.update("UPDATE sys_user SET status=1,deleted=1 WHERE id=?", target.id());
        assertCode(() -> owners.initialize(target.id(), target.username(), "operator"), ErrorCode.CONFLICT);
        jdbc.update("UPDATE sys_user SET deleted=0 WHERE id=?", target.id());
        assertThat(auth.getCurrentUser(token).role()).isEqualTo(UserRoleEnum.USER);
        assertThat(mapper.record().userId()).isNull();
    }

    @Test
    void shouldKeepOwnerSlotAfterSoftDeleteAndEnforceUniquenessForDirectDatabaseUpdates() {
        Fixture first = register();
        Fixture second = register();
        owners.initialize(first.id(), first.username(), "operator");
        jdbc.update("UPDATE sys_user SET status=0,deleted=1 WHERE id=?", first.id());
        assertCode(() -> owners.initialize(second.id(), second.username(), "operator"), ErrorCode.CONFLICT);
        assertThatThrownBy(() -> jdbc.update("UPDATE sys_user SET role=? WHERE id=?", UserRoleEnum.OWNER.getCode(), second.id()))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(mapper.ownerId()).isEqualTo(first.id());
        assertThat(mapper.target(second.id()).role()).isEqualTo(UserRoleEnum.USER);
    }

    @Test
    void shouldNotTreatManualDemotionOrDeletionAsPermissionToClaimAnotherOwner() {
        Fixture first = register();
        Fixture second = register();
        owners.initialize(first.id(), first.username(), "operator");
        jdbc.update("UPDATE sys_user SET role=? WHERE id=?", UserRoleEnum.USER.getCode(), first.id());
        assertCode(() -> owners.initialize(first.id(), first.username(), "operator"), ErrorCode.CONFLICT);
        assertCode(() -> owners.initialize(second.id(), second.username(), "operator"), ErrorCode.CONFLICT);
        assertThat(mapper.record().userId()).isEqualTo(first.id());
    }

    @Test
    void shouldRollbackRoleAndAuditWhenRedisFailsAndPermitRetry() {
        Fixture target = register();
        String token = login(target, "PC");
        doThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "测试安全故障"))
                .when(sessions).removeAllSessions(target.id());
        assertCode(() -> owners.initialize(target.id(), target.username(), "operator"), ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(mapper.target(target.id()).role()).isEqualTo(UserRoleEnum.USER);
        assertThat(mapper.record().userId()).isNull();
        assertThat(auth.getCurrentUser(token).role()).isEqualTo(UserRoleEnum.USER);
        doCallRealMethod().when(sessions).removeAllSessions(target.id());
        assertThat(owners.initialize(target.id(), target.username(), "operator")).isTrue();
    }

    @Test
    void shouldNotGrantRoleWhenSessionRemovalSucceededButThenReportedFailure() {
        Fixture target = register();
        String token = login(target, "PC");
        doAnswer(call -> {
            call.callRealMethod();
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "测试安全故障");
        }).when(sessions).removeAllSessions(target.id());
        assertCode(() -> owners.initialize(target.id(), target.username(), "operator"), ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(mapper.target(target.id()).role()).isEqualTo(UserRoleEnum.USER);
        assertThat(mapper.record().userId()).isNull();
        assertCode(() -> auth.getCurrentUser(token), ErrorCode.UNAUTHORIZED);
        doCallRealMethod().when(sessions).removeAllSessions(target.id());
        assertThat(owners.initialize(target.id(), target.username(), "operator")).isTrue();
    }

    @Test
    void shouldSerializeTwoCompetingConnectionsAndCommitExactlyOneOwner() throws Exception {
        Fixture first = register();
        Fixture second = register();
        AtomicLong firstConnection = new AtomicLong();
        AtomicLong secondConnection = new AtomicLong();
        CountDownLatch entered = new CountDownLatch(2);
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        List<Future<Boolean>> results = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            transaction.executeWithoutResult(status -> {
                mapper.lockRecord();
                results.add(executor.submit(() -> attempt(first, firstConnection, entered)));
                results.add(executor.submit(() -> attempt(second, secondConnection, entered)));
                try {
                    assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                    while ((liveLockWaitCount(firstConnection.get()) == 0 || liveLockWaitCount(secondConnection.get()) == 0)
                            && System.nanoTime() < deadline) Thread.sleep(20);
                    assertThat(liveLockWaitCount(firstConnection.get())).as("第一个连接实际等待初始化槽锁").isPositive();
                    assertThat(liveLockWaitCount(secondConnection.get())).as("第二个连接实际等待初始化槽锁").isPositive();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("验收被中断", error);
                }
            });
            List<Boolean> completed = List.of(results.get(0).get(20, TimeUnit.SECONDS), results.get(1).get(20, TimeUnit.SECONDS));
            assertThat(completed).containsExactlyInAnyOrder(true, false);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE owner_slot=1", Integer.class)).isEqualTo(1);
        assertThat(mapper.record().userId()).isEqualTo(mapper.ownerId());
    }

    @Test
    void shouldRunIndependentCommandWithoutHttpAndHonorPreviewApplyRepeatAndFailureExitCodes() throws Exception {
        Fixture target = register();
        runCli(target, false, target.username(), 0);
        assertThat(mapper.target(target.id()).role()).isEqualTo(UserRoleEnum.USER);
        assertThat(mapper.record().userId()).isNull();
        runCli(target, true, "another_account", 2);
        runCli(target, true, target.username(), 0);
        runCli(target, true, target.username(), 0);
        assertThat(mapper.target(target.id()).role()).isEqualTo(UserRoleEnum.OWNER);
        assertThat(mapper.record().userId()).isEqualTo(target.id());
    }

    @Test
    void shouldRejectAnOldSessionThatWasValidatedBeforeWaitingForThePromotionLock() throws Exception {
        Fixture target = register();
        String oldToken = login(target, "PC");
        AtomicLong readerConnection = new AtomicLong();
        CountDownLatch entered = new CountDownLatch(1);
        List<Future<ErrorCode>> result = new ArrayList<>();
        try (var executor = Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                mapper.lockRecord();
                mapper.lockTarget(target.id());
                result.add(executor.submit(() -> {
                    try {
                        new TransactionTemplate(transactions).executeWithoutResult(reader -> {
                            readerConnection.set(fixtures.connectionId());
                            entered.countDown();
                            auth.getCurrentUser(oldToken);
                        });
                        throw new AssertionError("旧会话不能获得升权后的身份");
                    } catch (BusinessException rejected) {
                        return rejected.getErrorCode();
                    }
                }));
                try {
                    assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                    while (liveLockWaitCount(readerConnection.get()) == 0 && System.nanoTime() < deadline) Thread.sleep(20);
                    assertThat(liveLockWaitCount(readerConnection.get())).as("旧会话已通过 Redis 初检并实际等待用户行锁").isPositive();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("验收被中断", error);
                }
                owners.initialize(target.id(), target.username(), "operator");
            });
            assertThat(result.get(0).get(20, TimeUnit.SECONDS)).isEqualTo(ErrorCode.UNAUTHORIZED);
        }
        assertThat(mapper.target(target.id()).role()).isEqualTo(UserRoleEnum.OWNER);
    }

    private boolean attempt(Fixture target, AtomicLong connection, CountDownLatch entered) {
        try {
            return Boolean.TRUE.equals(new TransactionTemplate(transactions).execute(status -> {
                connection.set(fixtures.connectionId());
                entered.countDown();
                return owners.initialize(target.id(), target.username(), "concurrent-operator");
            }));
        } catch (BusinessException rejected) {
            assertThat(rejected.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
            return false;
        }
    }

    private int liveLockWaitCount(long connectionId) {
        // 在持锁事务内轮询时绕过 MyBatis 一级查询缓存，必须观察实时等待关系，不能重复读取首次的零值。
        return jdbc.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits waits "
                + "JOIN performance_schema.threads threads ON threads.THREAD_ID=waits.REQUESTING_THREAD_ID "
                + "WHERE threads.PROCESSLIST_ID=?", Integer.class, connectionId);
    }

    private void runCli(Fixture target, boolean apply, String username, int exit) throws Exception {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                OwnerBootstrapCommand.class.getName(), "--user-id=" + target.id(), "--username=" + username));
        if (apply) command.add("--apply");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            assertThat(process.waitFor(40, TimeUnit.SECONDS)).as("独立命令必须退出，不能留下 Web 或 Redis 线程").isTrue();
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(process.exitValue()).as("命令结果（原始输出仅在本地检查，不回显凭据）").isEqualTo(exit);
            assertThat(output).doesNotContain("Tomcat started", target.password());
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private Fixture register() {
        String username = "owner_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String password = UUID.randomUUID().toString();
        var profile = auth.register(new RegisterDTO(username, password, "屋主验收"));
        Fixture target = new Fixture(Long.parseLong(profile.id()), username, password);
        owned.add(target);
        return target;
    }

    private String login(Fixture fixture, String client) {
        return auth.login(new LoginDTO(fixture.username(), fixture.password(), client), null).accessToken();
    }

    private static List<String> keys(long id) {
        return List.of("PC", "H5", "PLUGIN").stream().map(client -> RedisConstants.buildAuthTokenKey(id, client)).toList();
    }

    private static void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }

    private record Fixture(long id, String username, String password) {
        @Override
        public String toString() { return "OwnerFixture[id=" + id + "]"; }
    }
}
