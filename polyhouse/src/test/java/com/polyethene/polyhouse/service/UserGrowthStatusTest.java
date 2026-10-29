package com.polyethene.polyhouse.service;

import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.constant.UserGrowthRules;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.mapper.UserGrowthMapper;
import com.polyethene.polyhouse.mapper.UserMapper;
import com.polyethene.polyhouse.model.entity.User;
import com.polyethene.polyhouse.model.entity.DailyLoginReward;
import com.polyethene.polyhouse.service.impl.UserGrowthServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** 验证展示进度的门槛、满级大整数和服务端日期，读取状态自身不能制造奖励。 */
@ExtendWith(MockitoExtension.class)
class UserGrowthStatusTest {
    @Mock
    private UserMapper users;
    @Mock
    private UserGrowthMapper rewards;
    private UserGrowthServiceImpl service;

    @BeforeEach
    void useServerClockJustBeforeShanghaiMidnight() {
        service = new UserGrowthServiceImpl(users, rewards,
                Clock.fixed(Instant.parse("2026-10-27T15:59:59Z"), ZoneOffset.UTC));
    }

    @Test
    void shouldRejectStaleVersionBeforeRewardLedgerOrProtectedRead() {
        User user = active(10);
        user.setCredentialVersion(1L);
        when(users.selectOne(any())).thenReturn(user);
        assertThatThrownBy(() -> service.rewardDailyLogin(42, 0)).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
        assertThatThrownBy(() -> service.getStatus(42, 0)).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
        verifyNoInteractions(rewards);
    }

    @Test
    void shouldReturnNecessaryProfileWithoutRewardsForInitialCredentialsAndRefuseGrowthRead() {
        User user = active(0);
        user.setPasswordChangeRequired(true);
        when(users.selectOne(any())).thenReturn(user);
        assertThat(service.rewardDailyLogin(42, 0)).isSameAs(user);
        assertThatThrownBy(() -> service.getStatus(42, 0)).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PASSWORD_CHANGE_REQUIRED));
        verifyNoInteractions(rewards);
    }

    @ParameterizedTest
    @CsvSource(value = {"0,1,30,30,0", "10,1,30,20,33", "29,1,30,1,96", "30,2,200,170,0",
            "199,2,200,1,99", "200,3,580,380,0", "579,3,580,1,99", "580,4,1370,790,0",
            "1369,4,1370,1,99", "1370,5,3650,2280,0", "3649,5,3650,1,99",
            "3650,6,null,0,100", "9223372036854775807,6,null,0,100"}, nullValues = "null")
    void shouldProjectProgressWithoutOverflowOrWritingRewards(long experience, int level, String next,
                                                              String remaining, int percent) {
        User user = active(experience);
        when(users.selectOne(any())).thenReturn(user);
        // 显式模拟 SQL 的“无行”结果，不能使用包装整数 mock 的默认零值代替没有流水。
        when(rewards.findRewardDetailsForUpdate(42L, UserGrowthRules.DAILY_LOGIN_ACTION, LocalDate.of(2026, 10, 27))).thenReturn(null);
        var status = service.getStatus(42L);
        assertThat(status.experience()).isEqualTo(Long.toString(experience));
        assertThat(status.coins()).isEqualTo(Long.toString(Long.MAX_VALUE));
        assertThat(status.level()).isEqualTo(level);
        assertThat(status.nextLevelExperience()).isEqualTo(next);
        assertThat(status.experienceToNextLevel()).isEqualTo(remaining);
        assertThat(status.progressPercent()).isEqualTo(percent);
        assertThat(status.maxLevelReached()).isEqualTo(level == UserGrowthRules.MAX_LEVEL);
        assertThat(status.dailyLoginRewardClaimed()).isFalse();
        verify(users).selectOne(any());
        verify(rewards).findRewardDetailsForUpdate(42L, UserGrowthRules.DAILY_LOGIN_ACTION, LocalDate.of(2026, 10, 27));
        verifyNoMoreInteractions(users, rewards);
    }

    @Test
    void shouldUseLedgerAndShanghaiDayWithoutClientClock() {
        when(users.selectOne(any())).thenReturn(active(10));
        when(rewards.findRewardDetailsForUpdate(42L, UserGrowthRules.DAILY_LOGIN_ACTION, LocalDate.of(2026, 10, 27)))
                .thenReturn(new DailyLoginReward(7, 2));
        var status = service.getStatus(42L);
        assertThat(status.dailyLoginRewardClaimed()).isTrue();
        assertThat(status.rewardDate()).isEqualTo(LocalDate.of(2026, 10, 27));
        assertThat(status.nextRewardAt()).isEqualTo(Instant.parse("2026-10-27T16:00:00Z"));
        assertThat(status.observedAt()).isEqualTo(Instant.parse("2026-10-27T15:59:59Z"));
        assertThat(status.rewardTimeZone()).isEqualTo("Asia/Shanghai");
        assertThat(status.dailyLoginExperience()).isEqualTo(7);
        assertThat(status.dailyLoginCoins()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void shouldRejectInvalidIdentityBeforeStorage(long userId) {
        assertCode(() -> service.getStatus(userId), ErrorCode.INVALID_PARAMETER);
        verifyNoInteractions(users, rewards);
    }

    @Test
    void shouldRejectInactiveOrDeletedUsers() {
        User disabled = active(10);
        disabled.setStatus(0);
        when(users.selectOne(any())).thenReturn(disabled);
        assertCode(() -> service.getStatus(42L), ErrorCode.UNAUTHORIZED);
        User deleted = active(10);
        deleted.setDeleted(1);
        when(users.selectOne(any())).thenReturn(deleted);
        assertCode(() -> service.getStatus(42L), ErrorCode.UNAUTHORIZED);
        verifyNoInteractions(rewards);
    }

    @Test
    void shouldRejectInvalidBalancesRatherThanInventingProgress() {
        User invalid = active(-1);
        when(users.selectOne(any())).thenReturn(invalid);
        assertCode(() -> service.getStatus(42L), ErrorCode.SERVICE_UNAVAILABLE);
        invalid.setExperience(10L);
        invalid.setCoins(null);
        assertCode(() -> service.getStatus(42L), ErrorCode.SERVICE_UNAVAILABLE);
        verifyNoInteractions(rewards);
    }

    @Test
    void shouldNeverTreatLedgerFailureAsUnclaimed() {
        when(users.selectOne(any())).thenReturn(active(10));
        when(rewards.findRewardDetailsForUpdate(42L, UserGrowthRules.DAILY_LOGIN_ACTION, LocalDate.of(2026, 10, 27)))
                .thenThrow(new DataAccessResourceFailureException("private-storage-details"));
        assertCode(() -> service.getStatus(42L), ErrorCode.SERVICE_UNAVAILABLE);
    }

    private static User active(long experience) {
        User user = new User();
        user.setId(42L);
        user.setRole(UserRoleEnum.USER);
        user.setStatus(1);
        user.setDeleted(0);
        user.setExperience(experience);
        user.setCoins(Long.MAX_VALUE);
        return user;
    }

    private static void assertCode(Runnable operation, ErrorCode code) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(BusinessException.class, exception -> {
            assertThat(exception.getErrorCode()).isEqualTo(code);
            assertThat(exception.getMessage()).doesNotContain("private-storage-details");
            assertThat(exception.getCause()).isNull();
        });
    }
}
