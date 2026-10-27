package com.polyethene.polyhouse.model.entity;

/**
 * 当日流水的内部读取投影，供展示服务使用，不能直接作为 Controller 响应。
 *
 * @param experienceDelta 当日实际入账经验，历史流水不能按当前规则重新解释
 * @param coinsDelta 当日实际入账硬币，与余额读取保持同一锁顺序
 */
public record DailyLoginReward(int experienceDelta, int coinsDelta) {
}
