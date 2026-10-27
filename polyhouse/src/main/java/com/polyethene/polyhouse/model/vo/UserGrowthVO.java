package com.polyethene.polyhouse.model.vo;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 成长展示的服务端快照；门槛、进度和结算日来自同一规则及锁定读，不由浏览器猜测到账。
 *
 * @param userId 绑定当前用户，字符串保留雪花 ID 精度
 * @param experience 当前累计经验，字符串保留 bigint 精度
 * @param coins 当前硬币余额，与经验在同一用户快照读取
 * @param level 按现行规则计算的成长等级，不代表角色权限
 * @param maxLevel 当前规则等级上限
 * @param maxLevelReached 是否已经满级，满级经验仍继续累计
 * @param levelStartExperience 当前等级的累计经验起点
 * @param nextLevelExperience 下一级累计门槛，满级时为 null，避免虚构下一级
 * @param experienceToNextLevel 距下一级所需经验，满级时固定为字符串 0
 * @param progressPercent 当前等级内的整数百分比，向下取整；满级为 100
 * @param rewardDate 此快照对应的服务端自然日
 * @param rewardTimeZone 服务端结算时区，客户端时区不会增加领取机会
 * @param dailyLoginRewardClaimed 当日真实流水是否已经存在，不使用缓存布尔值代替
 * @param dailyLoginExperience 已到账时为真实流水经验数，尚未到账时为当前规则的预期值
 * @param dailyLoginCoins 已到账时为真实流水硬币数，尚未到账时为当前规则的预期值
 * @param observedAt 捕获快照的服务端时刻，用于计算相对刷新间隔，避免依赖客户端时钟
 * @param nextRewardAt 服务端下一自然日零点，跨日后应重新查询
 * @param ruleVersion 当前门槛及未到账预期奖励的规则版本；已到账数额读取原流水，不创建新的领取机会
 */
public record UserGrowthVO(String userId, String experience, String coins, int level, int maxLevel,
                           boolean maxLevelReached, String levelStartExperience, String nextLevelExperience,
                           String experienceToNextLevel, int progressPercent, LocalDate rewardDate,
                           String rewardTimeZone, boolean dailyLoginRewardClaimed, int dailyLoginExperience,
                           int dailyLoginCoins, Instant observedAt, Instant nextRewardAt, String ruleVersion) {
}
