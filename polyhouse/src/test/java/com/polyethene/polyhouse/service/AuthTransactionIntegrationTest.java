package com.polyethene.polyhouse.service;

import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.constant.RedisConstants;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.mapper.UserMapper;
import com.polyethene.polyhouse.model.dto.LoginDTO;
import com.polyethene.polyhouse.model.dto.RegisterDTO;
import com.polyethene.polyhouse.model.vo.LoginVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * 使用真实 MySQL 提交/回滚和 Redis 验收注销边界；故意不加测试类级 @Transactional，
 * 避免服务加入永不提交的外层测试事务，掩盖回滚或跨线程可见性问题。
 * 并发用例通过门闩控制关键位置，并以 MySQL 的实际锁等待作为竞争已经发生的证据。
 */
@EnabledIfSystemProperty(named = "polyhouse.auth.integration", matches = "true")
@SpringBootTest
class AuthTransactionIntegrationTest {

    private static final String TEST_KEY = temporaryKey();

    @Autowired
    private AuthService service;
    @Autowired
    private UserMapper users;
    @Autowired
    private AuthTransactionFixtureMapper fixturesMapper;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private PlatformTransactionManager transactions;
    @SpyBean
    private AuthRedisStore sessions;

    private final List<Fixture> ownedFixtures = new ArrayList<>();

    @DynamicPropertySource
    static void useOnlyEphemeralTestKey(DynamicPropertyRegistry properties) {
        properties.add("polyhouse.jwt.secret", () -> TEST_KEY);
    }

    @AfterEach
    void cleanupOnlyOwnedCommittedFixtures() {
        reset(sessions);
        List<Throwable> failures = new ArrayList<>();
        for (Fixture fixture : ownedFixtures) {
            // 即使中途断言失败也会清理本测试登记的三类会话，绝不扫描业务键或按账号前缀删除。
            try {
                try {
                    redis.delete(sessionKeys(fixture.id()));
                } finally {
                    assertThat(fixturesMapper.removeOwnedFixture(fixture.id(), fixture.username())).isEqualTo(1);
                    assertThat(fixturesMapper.deleted(fixture.id(), fixture.username())).isNull();
                }
            } catch (RuntimeException | AssertionError exception) {
                // 某个夹具清理失败时仍尝试其他已登记记录，最后统一报错，不能静默留下测试账号。
                failures.add(exception);
            }
        }
        assertThat(failures).as("所有已登记测试记录及会话都必须清理成功").isEmpty();
    }

    @Test
    void shouldCommitSoftDeletionRevokeEveryClientAndKeepOtherUserOnline() {
        Fixture target = registerFixture();
        Fixture other = registerFixture();
        LoginVO pc = login(target, "PC");
        LoginVO h5 = login(target, "H5");
        LoginVO plugin = login(target, "PLUGIN");
        LoginVO unrelated = login(other, "PC");

        service.deregister(pc.accessToken());

        assertDeletedAndNoSessions(target);
        for (LoginVO previous : List.of(pc, h5, plugin)) {
            assertCode(() -> service.getCurrentUser(previous.accessToken()), ErrorCode.UNAUTHORIZED);
        }
        assertCode(() -> login(target, "PC"), ErrorCode.UNAUTHORIZED);
        assertCode(() -> service.register(new RegisterDTO(target.username(), target.password(), "仍占用的账号")),
                ErrorCode.CONFLICT);
        assertThat(service.getCurrentUser(unrelated.accessToken()).id()).isEqualTo(Long.toString(other.id()));
    }

    @Test
    void shouldRollbackSoftDeletionWhenRedisCleanupFailsAndPermitRetry() {
        Fixture fixture = registerFixture();
        LoginVO pc = login(fixture, "PC");
        LoginVO h5 = login(fixture, "H5");
        doThrow(unavailable()).when(sessions).removeAllSessions(fixture.id());

        assertCode(() -> service.deregister(pc.accessToken()), ErrorCode.SERVICE_UNAVAILABLE);

        // 服务调用已经退出事务；这里重新查库，不能让同一测试事务的回滚伪装成服务正确回滚。
        assertThat(fixturesMapper.deleted(fixture.id(), fixture.username())).isZero();
        assertThat(service.getCurrentUser(pc.accessToken()).id()).isEqualTo(Long.toString(fixture.id()));
        assertThat(service.getCurrentUser(h5.accessToken()).id()).isEqualTo(Long.toString(fixture.id()));
        doCallRealMethod().when(sessions).removeAllSessions(fixture.id());
        service.deregister(pc.accessToken());
        assertDeletedAndNoSessions(fixture);
    }

    @Test
    void shouldRollbackDatabaseButNotRestoreRedisAfterCleanupAcknowledgementFailure() {
        Fixture fixture = registerFixture();
        LoginVO pc = login(fixture, "PC");
        doAnswer(invocation -> {
            invocation.callRealMethod();
            // 模拟 DEL 已执行但确认响应丢失：数据库能回滚，Redis 已删除的键不能假装自动恢复。
            throw unavailable();
        }).when(sessions).removeAllSessions(fixture.id());

        assertCode(() -> service.deregister(pc.accessToken()), ErrorCode.SERVICE_UNAVAILABLE);

        assertThat(fixturesMapper.deleted(fixture.id(), fixture.username())).isZero();
        assertThat(redis.countExistingKeys(sessionKeys(fixture.id()))).isZero();
        assertCode(() -> service.getCurrentUser(pc.accessToken()), ErrorCode.UNAUTHORIZED);
        assertThat(login(fixture, "PC").user().id()).isEqualTo(Long.toString(fixture.id()));
    }

    @Test
    void shouldRollbackLoginAuditWhenSessionPublicationFails() {
        Fixture fixture = registerFixture();
        assertThat(users.selectById(fixture.id()).getLastLoginTime()).isNull();
        doThrow(unavailable()).when(sessions).saveSession(any(), any());

        assertCode(() -> service.login(new LoginDTO(fixture.username(), fixture.password()), "192.0.2.10"),
                ErrorCode.SERVICE_UNAVAILABLE);

        var persisted = users.selectById(fixture.id());
        assertThat(persisted.getLastLoginTime()).isNull();
        assertThat(persisted.getLastLoginIp()).isNull();
        assertThat(redis.countExistingKeys(sessionKeys(fixture.id()))).isZero();
    }

    @RepeatedTest(3)
    void shouldWaitForPendingLoginThenDeleteItsPublishedSession() throws Exception {
        Fixture fixture = registerFixture();
        LoginVO pc = login(fixture, "PC");
        CountDownLatch loginHasLock = new CountDownLatch(1);
        CountDownLatch releaseLogin = new CountDownLatch(1);
        doAnswer(invocation -> {
            // 此时登录审计 UPDATE 已成功，用户行锁应一直持有到会话发布后事务提交。
            loginHasLock.countDown();
            requireSignal(releaseLogin);
            return invocation.callRealMethod();
        }).when(sessions).saveSession(any(), any());

        try (var executor = Executors.newFixedThreadPool(2)) {
            var login = executor.submit(() -> login(fixture, "H5"));
            try {
                requireSignal(loginHasLock);
                AtomicLong deregisterConnection = new AtomicLong();
                var cancel = executor.submit(() -> inTransaction(deregisterConnection, () -> {
                    service.deregister(pc.accessToken());
                    return null;
                }));
                awaitActualLockWait(deregisterConnection, cancel);
                releaseLogin.countDown();
                LoginVO concurrentLogin = login.get(15, TimeUnit.SECONDS);
                cancel.get(15, TimeUnit.SECONDS);
                assertDeletedAndNoSessions(fixture);
                assertCode(() -> service.getCurrentUser(concurrentLogin.accessToken()), ErrorCode.UNAUTHORIZED);
            } finally {
                releaseLogin.countDown();
            }
        }
    }

    @RepeatedTest(3)
    void shouldRejectPendingLoginAfterDeregistrationCommits() throws Exception {
        Fixture fixture = registerFixture();
        LoginVO pc = login(fixture, "PC");
        CountDownLatch deletionHasLock = new CountDownLatch(1);
        CountDownLatch releaseDeletion = new CountDownLatch(1);
        doAnswer(invocation -> {
            // 软删除 UPDATE 已执行但未提交；另一事务仍能读到旧账号，却必须在审计 UPDATE 时等待。
            deletionHasLock.countDown();
            requireSignal(releaseDeletion);
            return invocation.callRealMethod();
        }).when(sessions).removeAllSessions(fixture.id());

        try (var executor = Executors.newFixedThreadPool(2)) {
            var cancel = executor.submit(() -> service.deregister(pc.accessToken()));
            try {
                requireSignal(deletionHasLock);
                AtomicLong loginConnection = new AtomicLong();
                var login = executor.submit(() -> inTransaction(loginConnection, () -> login(fixture, "H5")));
                awaitActualLockWait(loginConnection, login);
                releaseDeletion.countDown();
                cancel.get(15, TimeUnit.SECONDS);
                // 登录异常在事务外断言，确保不会在同一事务内吞掉异常后触发 UnexpectedRollbackException。
                assertThatThrownBy(() -> login.get(15, TimeUnit.SECONDS))
                        .isInstanceOfSatisfying(ExecutionException.class, exception ->
                                assertThat(exception.getCause()).isInstanceOfSatisfying(BusinessException.class,
                                        cause -> assertThat(cause.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED)));
                assertDeletedAndNoSessions(fixture);
            } finally {
                releaseDeletion.countDown();
            }
        }
    }

    private Fixture registerFixture() {
        String username = "auth_tx_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String password = UUID.randomUUID().toString();
        var profile = service.register(new RegisterDTO(username, password, "独立事务验收"));
        Fixture fixture = new Fixture(Long.parseLong(profile.id()), username, password);
        ownedFixtures.add(fixture);
        return fixture;
    }

    private LoginVO login(Fixture fixture, String client) {
        return service.login(new LoginDTO(fixture.username(), fixture.password(), client), null);
    }

    private <T> T inTransaction(AtomicLong connectionId, Supplier<T> operation) {
        return new TransactionTemplate(transactions).execute(status -> {
            connectionId.set(fixturesMapper.connectionId());
            return operation.get();
        });
    }

    private void awaitActualLockWait(AtomicLong connectionId, Future<?> contender) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            long id = connectionId.get();
            if (id > 0 && fixturesMapper.lockWaitCount(id) > 0) {
                return;
            }
            if (contender.isDone()) {
                contender.get();
                throw new AssertionError("竞争事务在观测到行锁等待前已完成");
            }
            TimeUnit.MILLISECONDS.sleep(40);
        }
        throw new AssertionError("未观测到测试连接的真实 MySQL 行锁等待");
    }

    private void assertDeletedAndNoSessions(Fixture fixture) {
        assertThat(fixturesMapper.deleted(fixture.id(), fixture.username())).isEqualTo(1);
        assertThat(users.selectById(fixture.id())).isNull();
        assertThat(redis.countExistingKeys(sessionKeys(fixture.id()))).isZero();
    }

    private static List<String> sessionKeys(long userId) {
        return List.of("PC", "H5", "PLUGIN").stream()
                .map(client -> RedisConstants.buildAuthTokenKey(userId, client)).toList();
    }

    private static void requireSignal(CountDownLatch signal) throws InterruptedException {
        // 门闩超时长于十秒锁观察期，让失败时先通过 finally 释放线程，不让门闩自行超时干扰结论。
        if (!signal.await(30, TimeUnit.SECONDS)) {
            throw new AssertionError("测试同步门闩等待超时");
        }
    }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "认证服务暂时不可用，请稍后重试");
    }

    private static void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }

    private static String temporaryKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private record Fixture(long id, String username, String password) {
        /** 测试夹具默认文本也不能泄露随机密码。 */
        @Override
        public String toString() {
            return "Fixture[credentials redacted]";
        }
    }
}
