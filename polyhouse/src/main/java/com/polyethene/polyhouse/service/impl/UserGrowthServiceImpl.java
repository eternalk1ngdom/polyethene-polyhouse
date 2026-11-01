package com.polyethene.polyhouse.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.constant.UserGrowthRules;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.common.security.SessionSecurity;
import com.polyethene.polyhouse.mapper.UserGrowthMapper;
import com.polyethene.polyhouse.mapper.UserMapper;
import com.polyethene.polyhouse.model.entity.User;
import com.polyethene.polyhouse.model.entity.DailyLoginReward;
import com.polyethene.polyhouse.model.vo.UserGrowthVO;
import com.polyethene.polyhouse.service.UserGrowthService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/** 在一个事务中完成用户行锁、防重流水、经验/硬币入账和等级计算，与退出及软注销共用用户行锁。 */
@Service
public class UserGrowthServiceImpl implements UserGrowthService {

    private final UserMapper users;
    private final UserGrowthMapper rewards;
    private final Clock clock;

    /** 依赖由容器提供；时钟具名注入，避免未来其他业务时钟改变奖励日的来源。 */
    public UserGrowthServiceImpl(UserMapper users, UserGrowthMapper rewards,
                                 @Qualifier("userGrowthClock") Clock clock) {
        this.users = users;
        this.rewards = rewards;
        this.clock = clock;
    }

    /**
     * 与外层登录事务保持 REQUIRED 传播；Redis 会话发布失败时，登录审计、奖励流水及余额一起回滚。
     * 已有有效 Token 的认证调用也会进入此方法，使跨日恢复登录无需退出后重新输入密码。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public User rewardDailyLogin(long userId) {
        return rewardLocked(userId, null);
    }

    /** 与认证版本核对共用同一次锁定读，不增加每请求的完整认证或数据库查询。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public User rewardDailyLogin(long userId, long credentialVersion) {
        return rewardLocked(userId, credentialVersion);
    }

    private User rewardLocked(long userId, Long credentialVersion) {
        if (userId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER);
        }
        try {
            // 锁定读必须排在流水读之前，所有结算统一按“用户 -> 流水”加锁，防止重复奖励和锁顺序反转。
            User user = users.selectOne(new LambdaQueryWrapper<User>().eq(User::getId, userId).last("FOR UPDATE"));
            if (user == null || !Objects.equals(user.getStatus(), 1) || !Objects.equals(user.getDeleted(), 0)
                    || user.getRole() == null) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "登录状态已失效，请重新登录");
            }
            SessionSecurity.requireCurrent(user, credentialVersion == null
                    ? Objects.requireNonNullElse(user.getCredentialVersion(), -1L) : credentialVersion);
            // 临时凭据尚未完成正式登录，不发放成长奖励，也不读取无关流水；本人资料仍可用于改密。
            if (Boolean.TRUE.equals(user.getPasswordChangeRequired())) return user;
            LocalDate rewardDate = LocalDate.ofInstant(clock.instant(), UserGrowthRules.REWARD_ZONE);
            if (rewards.findRewardForUpdate(userId, UserGrowthRules.DAILY_LOGIN_ACTION, rewardDate) != null) {
                return user;
            }
            if (user.getExperience() == null || user.getExperience() < 0 || user.getCoins() == null || user.getCoins() < 0) {
                throw unavailable();
            }
            // 检查 long 溢出，不能让余额绕回负数；满级只限制等级，累计经验不截断或清零。
            long experience = Math.addExact(user.getExperience(), UserGrowthRules.DAILY_LOGIN_EXPERIENCE);
            long coins = Math.addExact(user.getCoins(), UserGrowthRules.DAILY_LOGIN_COINS);
            int level = UserGrowthRules.levelForExperience(experience);
            if (rewards.insertReward(userId, UserGrowthRules.DAILY_LOGIN_ACTION, rewardDate,
                    UserGrowthRules.DAILY_LOGIN_EXPERIENCE, UserGrowthRules.DAILY_LOGIN_COINS,
                    UserGrowthRules.RULE_VERSION) != 1) {
                throw unavailable();
            }
            // 只更新成长列，不能把认证前的旧角色、密码、昵称或审计快照覆盖回数据库。
            if (users.update(null, new LambdaUpdateWrapper<User>().eq(User::getId, userId)
                    .eq(User::getStatus, 1).set(User::getExperience, experience)
                    .set(User::getLevel, level).set(User::getCoins, coins)) != 1) {
                throw unavailable();
            }
            user.setExperience(experience);
            user.setLevel(level);
            user.setCoins(coins);
            return user;
        } catch (DataAccessException | ArithmeticException exception) {
            // 异常穿过事务代理触发回滚；安全提示不包含 SQL、余额、凭据或底层异常链。
            throw unavailable();
        }
    }

    /**
     * 按“用户 -> 流水”的既有顺序读取，避免旧事务快照和并发注销产生混合资料。
     * FOR UPDATE 需要普通事务；此读取不修改余额，HTTP 的既有认证过程仍会自动结算每日登录。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserGrowthVO getStatus(long userId) {
        return readStatusLocked(userId, null);
    }

    /** 受保护 HTTP 查询不能绕过凭据版本及首次改密闸门。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserGrowthVO getStatus(long userId, long credentialVersion) {
        return readStatusLocked(userId, credentialVersion);
    }

    private UserGrowthVO readStatusLocked(long userId, Long credentialVersion) {
        if (userId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER);
        }
        try {
            User user = users.selectOne(new LambdaQueryWrapper<User>().eq(User::getId, userId).last("FOR UPDATE"));
            if (user == null || !Objects.equals(user.getStatus(), 1) || !Objects.equals(user.getDeleted(), 0)
                    || user.getRole() == null) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "登录状态已失效，请重新登录");
            }
            SessionSecurity.requireCurrent(user, credentialVersion == null
                    ? Objects.requireNonNullElse(user.getCredentialVersion(), -1L) : credentialVersion);
            SessionSecurity.requireFullAccess(user);
            if (user.getExperience() == null || user.getExperience() < 0 || user.getCoins() == null || user.getCoins() < 0) {
                throw unavailable();
            }
            // 等到用户行锁之后再捕获一次时间，避免跨零点等待锁时把今日状态算成昨日。
            Instant observedAt = clock.instant();
            LocalDate rewardDate = LocalDate.ofInstant(observedAt, UserGrowthRules.REWARD_ZONE);
            DailyLoginReward daily = rewards.findRewardDetailsForUpdate(userId, UserGrowthRules.DAILY_LOGIN_ACTION, rewardDate);
            if (daily != null && (daily.experienceDelta() < 0 || daily.coinsDelta() < 0)) {
                throw unavailable();
            }
            boolean claimed = daily != null;
            long experience = user.getExperience();
            int level = UserGrowthRules.levelForExperience(experience);
            boolean maximum = level == UserGrowthRules.MAX_LEVEL;
            long start = UserGrowthRules.experienceForLevel(level);
            Long next = maximum ? null : UserGrowthRules.experienceForLevel(level + 1);
            // 非满级经验严格处于相邻门槛内，乘 100 的中间值有界；满级单独处理，Long.MAX_VALUE 也不会溢出。
            int percent = maximum ? 100 : (int) ((experience - start) * 100 / (next - start));
            long remaining = maximum ? 0 : next - experience;
            return new UserGrowthVO(Long.toString(userId), Long.toString(experience), user.getCoins().toString(),
                    level, UserGrowthRules.MAX_LEVEL, maximum, Long.toString(start),
                    next == null ? null : next.toString(), Long.toString(remaining), percent,
                    rewardDate, UserGrowthRules.REWARD_ZONE.getId(), claimed,
                    claimed ? daily.experienceDelta() : UserGrowthRules.DAILY_LOGIN_EXPERIENCE,
                    claimed ? daily.coinsDelta() : UserGrowthRules.DAILY_LOGIN_COINS,
                    observedAt, rewardDate.plusDays(1).atStartOfDay(UserGrowthRules.REWARD_ZONE).toInstant(),
                    UserGrowthRules.RULE_VERSION);
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "成长奖励暂时不可用，请稍后重试");
    }
}
