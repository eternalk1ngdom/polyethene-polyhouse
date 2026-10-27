package com.polyethene.polyhouse.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.constant.RedisConstants;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.mapper.UserGrowthMapper;
import com.polyethene.polyhouse.mapper.UserMapper;
import com.polyethene.polyhouse.model.dto.LoginDTO;
import com.polyethene.polyhouse.model.dto.RegisterDTO;
import com.polyethene.polyhouse.model.entity.User;
import com.polyethene.polyhouse.model.vo.UserProfileVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.mybatis.spring.SqlSessionTemplate;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * 真实 MySQL/Redis 成长验收，服务事务实际提交；仅替换奖励时钟来模拟跨日和一年登录。
 * 流水防重、并发和回滚均使用真实存储，测试不依赖外层统一回滚掩盖业务错误。
 */
@EnabledIfSystemProperty(named = "polyhouse.auth.integration", matches = "true")
@SpringBootTest
class UserGrowthIntegrationTest {

    private static final Instant FIRST_DAY = Instant.parse("2026-01-01T04:00:00Z");
    private static final String TEST_KEY = temporaryKey();

    @Autowired
    private AuthService auth;
    @Autowired
    private UserGrowthService growth;
    @Autowired
    private UserMapper users;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AuthTransactionFixtureMapper fixtures;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private PlatformTransactionManager transactions;
    @Autowired
    private SqlSessionTemplate sqlSession;
    @MockBean(name = "userGrowthClock")
    private Clock clock;
    @SpyBean
    private UserGrowthMapper rewards;

    private final List<Fixture> owned = new ArrayList<>();

    @DynamicPropertySource
    static void useEphemeralKeyOnlyForThisTestContext(DynamicPropertyRegistry properties) {
        properties.add("polyhouse.jwt.secret", () -> TEST_KEY);
    }

    @BeforeEach
    void startAtFixedRewardDay() {
        when(clock.instant()).thenReturn(FIRST_DAY);
    }

    @AfterEach
    void cleanupExactOwnedUsersSessionsAndCascadedRewards() {
        reset(rewards);
        List<Throwable> failures = new ArrayList<>();
        for (Fixture fixture : owned) {
            try {
                try {
                    redis.delete(List.of("PC", "H5", "PLUGIN").stream()
                            .map(client -> RedisConstants.buildAuthTokenKey(fixture.id(), client)).toList());
                } finally {
                    assertThat(fixtures.removeOwnedFixture(fixture.id(), fixture.username())).isEqualTo(1);
                    assertThat(rewardCount(fixture.id())).isZero();
                }
            } catch (RuntimeException | AssertionError exception) {
                // 清理某条夹具失败不跳过剩余记录，最后统一报告，不静默留下已提交数据。
                failures.add(exception);
            }
        }
        assertThat(failures).as("本测试精确归属的用户、奖励和会话必须全部清理").isEmpty();
    }

    @Test
    void shouldReachLevelSixOn365thRewardedDayAndContinueAccumulatingAfterwards() {
        Fixture fixture = register();
        var first = auth.login(new LoginDTO(fixture.username(), fixture.password()), null);
        assertThat(first.user().experience()).isEqualTo("10");
        for (int day = 1; day <= 365; day++) {
            when(clock.instant()).thenReturn(FIRST_DAY.plus(day - 1L, ChronoUnit.DAYS));
            UserProfileVO current = auth.getCurrentUser(first.accessToken());
            assertThat(current.experience()).isEqualTo(Integer.toString(day * 10));
            assertThat(current.coins()).isEqualTo(Integer.toString(day));
            assertThat(current.role()).isEqualTo(UserRoleEnum.USER);
            if (day == 364) { assertThat(current.level()).isEqualTo(5); }
            if (day == 365) { assertThat(current.level()).isEqualTo(6); }
        }
        assertThat(rewardCount(fixture.id())).isEqualTo(365);
        assertThat(jdbc.queryForObject("SELECT SUM(experience_delta) FROM sys_user_growth_reward WHERE user_id = ?",
                Long.class, fixture.id())).isEqualTo(3650);
        when(clock.instant()).thenReturn(FIRST_DAY.plus(365, ChronoUnit.DAYS));
        UserProfileVO afterMax = auth.getCurrentUser(first.accessToken());
        assertThat(afterMax.experience()).isEqualTo("3660");
        assertThat(afterMax.level()).isEqualTo(6);
        assertThat(afterMax.coins()).isEqualTo("366");
    }

    @Test
    void shouldShareDailyLimitAcrossClientsAndUseShanghaiMidnightWithoutBackfill() {
        Fixture fixture = register();
        when(clock.instant()).thenReturn(Instant.parse("2026-10-26T15:59:59Z"));
        var pc = auth.login(new LoginDTO(fixture.username(), fixture.password(), "PC"), null);
        auth.login(new LoginDTO(fixture.username(), fixture.password(), "H5"), null);
        auth.login(new LoginDTO(fixture.username(), fixture.password(), "PLUGIN"), null);
        assertThat(auth.getCurrentUser(pc.accessToken()).experience()).isEqualTo("10");
        assertThat(rewardCount(fixture.id())).isEqualTo(1);
        // 只过一秒但进入上海次日，即可使用原 Token 领取一次，不要求过去满 24 小时。
        when(clock.instant()).thenReturn(Instant.parse("2026-10-26T16:00:00Z"));
        assertThat(auth.getCurrentUser(pc.accessToken()).experience()).isEqualTo("20");
        assertThat(auth.getCurrentUser(pc.accessToken()).coins()).isEqualTo("2");
        when(clock.instant()).thenReturn(Instant.parse("2026-12-01T04:00:00Z"));
        assertThat(auth.getCurrentUser(pc.accessToken()).experience()).isEqualTo("30");
        assertThat(rewardCount(fixture.id())).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT reward_date FROM sys_user_growth_reward WHERE user_id = ? ORDER BY reward_date",
                String.class, fixture.id())).containsExactly("2026-10-26", "2026-10-27", "2026-12-01");
    }

    @RepeatedTest(3)
    void shouldGrantOnlyOnceWhenEightTransactionsCompete() throws Exception {
        Fixture fixture = register();
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var pending = new ArrayList<java.util.concurrent.Future<User>>();
            for (int index = 0; index < 8; index++) {
                pending.add(executor.submit(() -> {
                    ready.countDown();
                    requireSignal(start);
                    return growth.rewardDailyLogin(fixture.id());
                }));
            }
            try {
                requireSignal(ready);
            } finally {
                start.countDown();
            }
            for (var future : pending) {
                User current = future.get(20, TimeUnit.SECONDS);
                assertThat(current.getExperience()).isEqualTo(10);
                assertThat(current.getCoins()).isEqualTo(1);
            }
        }
        assertThat(rewardCount(fixture.id())).isEqualTo(1);
    }

    @Test
    void shouldSeeCommittedRewardEvenWhenOuterTransactionHasAnOlderSnapshot() throws Exception {
        Fixture fixture = register();
        CountDownLatch snapshotReady = new CountDownLatch(1);
        CountDownLatch rewardCommitted = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var stale = executor.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                assertThat(rewardCount(fixture.id())).isZero();
                snapshotReady.countDown();
                requireSignal(rewardCommitted);
                return growth.rewardDailyLogin(fixture.id());
            }));
            try {
                requireSignal(snapshotReady);
                assertThat(growth.rewardDailyLogin(fixture.id()).getExperience()).isEqualTo(10);
            } finally {
                rewardCommitted.countDown();
            }
            assertThat(stale.get(20, TimeUnit.SECONDS).getExperience()).isEqualTo(10);
        }
        assertThat(rewardCount(fixture.id())).isEqualTo(1);
    }

    @Test
    void shouldRollbackInsertedLedgerAndBalanceTogetherThenPermitRetry() {
        Fixture fixture = register();
        // MyBatis Mapper 是接口代理，Mockito 不能对抽象接口 callRealMethod；
        // 从同一个事务感知 SqlSession 取得原生代理，实际插入后再模拟确认失败。
        UserGrowthMapper realMapper = sqlSession.getMapper(UserGrowthMapper.class);
        doAnswer(invocation -> {
            realMapper.insertReward(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2),
                    invocation.getArgument(3), invocation.getArgument(4), invocation.getArgument(5));
            throw new DataAccessResourceFailureException("growth-fixture-failure");
        }).when(rewards).insertReward(anyLong(), anyString(), any(), anyInt(), anyInt(), anyString());
        assertCode(() -> growth.rewardDailyLogin(fixture.id()), ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(rewardCount(fixture.id())).isZero();
        assertThat(users.selectById(fixture.id()).getExperience()).isZero();
        assertThat(users.selectById(fixture.id()).getCoins()).isZero();
        reset(rewards);
        assertThat(growth.rewardDailyLogin(fixture.id()).getExperience()).isEqualTo(10);
        assertThat(rewardCount(fixture.id())).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldRefuseOverflowWithoutPartialReward(boolean overflowCoins) {
        Fixture fixture = register();
        long experience = overflowCoins ? 0 : Long.MAX_VALUE;
        long coins = overflowCoins ? Long.MAX_VALUE : 0;
        assertThat(jdbc.update("UPDATE sys_user SET experience = ?, coins = ? WHERE id = ?",
                experience, coins, fixture.id())).isEqualTo(1);
        assertCode(() -> growth.rewardDailyLogin(fixture.id()), ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(rewardCount(fixture.id())).isZero();
        User current = users.selectById(fixture.id());
        assertThat(current.getExperience()).isEqualTo(experience);
        assertThat(current.getCoins()).isEqualTo(coins);
    }

    @Test
    void shouldRejectDisabledAndDeletedUsersWithoutLosingPriorLedger() {
        Fixture disabled = register();
        users.update(null, new LambdaUpdateWrapper<User>().eq(User::getId, disabled.id()).set(User::getStatus, 0));
        assertCode(() -> growth.rewardDailyLogin(disabled.id()), ErrorCode.UNAUTHORIZED);
        assertThat(rewardCount(disabled.id())).isZero();
        Fixture deleted = register();
        growth.rewardDailyLogin(deleted.id());
        users.deleteById(deleted.id());
        assertCode(() -> growth.rewardDailyLogin(deleted.id()), ErrorCode.UNAUTHORIZED);
        assertThat(rewardCount(deleted.id())).isEqualTo(1);
        assertCode(() -> growth.rewardDailyLogin(-1), ErrorCode.INVALID_PARAMETER);
    }

    @Test
    void shouldKeepDatabaseDailyLimitIndependentOfRuleVersionAndOtherUsers() {
        Fixture fixture = register();
        growth.rewardDailyLogin(fixture.id());
        // 即使未来规则版本改变，仍不能绕过同用户/行为/日期的主键再发一份奖励。
        assertThatThrownBy(() -> rewards.insertReward(fixture.id(), "DAILY_LOGIN", LocalDate.of(2026, 1, 1),
                10, 1, "future-rule-fixture")).isInstanceOf(DuplicateKeyException.class);
        assertThat(rewardCount(fixture.id())).isEqualTo(1);
        Fixture other = register();
        assertThat(growth.rewardDailyLogin(other.id()).getExperience()).isEqualTo(10);
        assertThat(rewardCount(other.id())).isEqualTo(1);
    }

    @Test
    void shouldReadStatusWithoutRewardAndRefreshAcrossShanghaiMidnight() {
        Fixture fixture = register();
        var before = growth.getStatus(fixture.id());
        assertThat(before.experience()).isEqualTo("0");
        assertThat(before.dailyLoginRewardClaimed()).isFalse();
        assertThat(rewardCount(fixture.id())).isZero();
        growth.rewardDailyLogin(fixture.id());
        var first = growth.getStatus(fixture.id());
        assertThat(first.dailyLoginRewardClaimed()).isTrue();
        assertThat(first.progressPercent()).isEqualTo(33);
        assertThat(first.experienceToNextLevel()).isEqualTo("20");
        when(clock.instant()).thenReturn(Instant.parse("2026-01-01T16:00:00Z"));
        var nextDayBeforeReward = growth.getStatus(fixture.id());
        assertThat(nextDayBeforeReward.rewardDate()).isEqualTo(LocalDate.of(2026, 1, 2));
        assertThat(nextDayBeforeReward.dailyLoginRewardClaimed()).isFalse();
        assertThat(nextDayBeforeReward.experience()).isEqualTo("10");
        assertThat(rewardCount(fixture.id())).isEqualTo(1);
        growth.rewardDailyLogin(fixture.id());
        var nextDayAfterReward = growth.getStatus(fixture.id());
        assertThat(nextDayAfterReward.dailyLoginRewardClaimed()).isTrue();
        assertThat(nextDayAfterReward.experience()).isEqualTo("20");
        assertThat(nextDayAfterReward.coins()).isEqualTo("2");
        assertThat(nextDayAfterReward.nextRewardAt()).isEqualTo(Instant.parse("2026-01-02T16:00:00Z"));
        assertThat(rewardCount(fixture.id())).isEqualTo(2);
    }

    @Test
    void shouldShowRecordedDailyAmountsInsteadOfReinterpretingHistoryWithCurrentRules() {
        Fixture fixture = register();
        assertThat(rewards.insertReward(fixture.id(), "DAILY_LOGIN", LocalDate.of(2026, 1, 1),
                5, 2, "historical-rule-fixture")).isEqualTo(1);
        assertThat(users.update(null, new LambdaUpdateWrapper<User>().eq(User::getId, fixture.id())
                .set(User::getExperience, 5L).set(User::getCoins, 2L).set(User::getLevel, 1))).isEqualTo(1);
        var status = growth.getStatus(fixture.id());
        assertThat(status.dailyLoginRewardClaimed()).isTrue();
        assertThat(status.dailyLoginExperience()).isEqualTo(5);
        assertThat(status.dailyLoginCoins()).isEqualTo(2);
        assertThat(status.experience()).isEqualTo("5");
        assertThat(rewardCount(fixture.id())).isEqualTo(1);
    }

    private Fixture register() {
        String username = "growth_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String password = UUID.randomUUID().toString();
        var profile = auth.register(new RegisterDTO(username, password, "成长事务验收"));
        Fixture fixture = new Fixture(Long.parseLong(profile.id()), username, password);
        owned.add(fixture);
        assertThat(profile.experience()).isEqualTo("0");
        return fixture;
    }

    private int rewardCount(long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sys_user_growth_reward WHERE user_id = ?", Integer.class, userId);
    }

    private static void requireSignal(CountDownLatch signal) {
        try {
            if (!signal.await(15, TimeUnit.SECONDS)) {
                throw new AssertionError("成长并发测试同步超时");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("成长并发测试被中断", exception);
        }
    }

    private static void assertCode(Runnable operation, ErrorCode code) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }

    private static String temporaryKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private record Fixture(long id, String username, String password) {
        /** 防止测试诊断直接输出随机密码。 */
        @Override
        public String toString() {
            return "Fixture[credentials redacted]";
        }
    }
}
