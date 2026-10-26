package com.polyethene.polyhouse.common.constant;

import java.time.ZoneId;

/** 集中定义成长规则，避免认证流程和未来界面分别维护升级公式；成长等级不授予角色权限。 */
public final class UserGrowthRules {

    /** 服务端按上海自然日结算，多端和客户端时区不能产生额外领取机会。 */
    public static final ZoneId REWARD_ZONE = ZoneId.of("Asia/Shanghai");
    /** 同类奖励的稳定标识不随规则版本变化，否则版本升级可能导致当日重复领取。 */
    public static final String DAILY_LOGIN_ACTION = "DAILY_LOGIN";
    /** 流水记录规则来源，后续改奖励数值时仍能解释历史结算。 */
    public static final String RULE_VERSION = "LOGIN_365_V1";
    /** 每日登录经验固定，365 个有效自然日累计达到 3650。 */
    public static final int DAILY_LOGIN_EXPERIENCE = 10;
    /** 硬币独立入账，不兑换经验，也不影响身份角色。 */
    public static final int DAILY_LOGIN_COINS = 1;
    /** 当前成长体系止于六级，满级后仍累计经验和每日硬币。 */
    public static final int MAX_LEVEL = 6;

    // 将 B 站累计门槛 0/200/1500/4500/10800/28800 按 365 天比例缩放，
    // 各级所需登录天数向上取整为 0/3/20/58/137/365，再乘每日 10 经验。
    private static final long[] THRESHOLDS = {0, 30, 200, 580, 1370, 3650};

    private UserGrowthRules() {
    }

    /** 根据累计经验计算最高已达到的等级；升级不扣经验，满级经验继续保留。 */
    public static int levelForExperience(long experience) {
        if (experience < 0) {
            throw new IllegalArgumentException("experience must not be negative");
        }
        for (int level = MAX_LEVEL; level > 1; level--) {
            if (experience >= THRESHOLDS[level - 1]) {
                return level;
            }
        }
        return 1;
    }

    /** 返回指定等级的累计门槛，便于服务和测试使用同一规则，不暴露可变数组。 */
    public static long experienceForLevel(int level) {
        if (level < 1 || level > MAX_LEVEL) {
            throw new IllegalArgumentException("level is outside the growth range");
        }
        return THRESHOLDS[level - 1];
    }
}
