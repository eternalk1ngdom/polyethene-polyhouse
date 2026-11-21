package com.polyethene.polyhouse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 独立显式启用自动受理；窄窗口不把进程休眠后的任意时间当成准时执行。
 * @param enabled 默认关闭，每日受理和补偿都需要此开关
 * @param dailyTime 北京HH:mm，默认06:30
 * @param retryDelayMinutes 首次后至少45分钟、至多120分钟，默认45；执行总时限仍为30分钟
 */
@ConfigurationProperties("polyhouse.trend.schedule")
public record TrendScheduleProperties(boolean enabled, String dailyTime, Integer retryDelayMinutes) {
    /** 不改变旧配置，缺省只补本模块参数；无效值不会让定时任务偷偷运行。 */
    public TrendScheduleProperties {
        dailyTime = dailyTime == null ? "06:30" : dailyTime;
        retryDelayMinutes = retryDelayMinutes == null ? 45 : retryDelayMinutes;
    }

    /** 每次窗口十分钟，两次都必须位于同一北京日期，避免跨日错用首次失败事实。 */
    public boolean ready() {
        if (!enabled || !dailyTime.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]")
                || retryDelayMinutes < 45 || retryDelayMinutes > 120) return false;
        return startMinute() + retryDelayMinutes + 10 <= 1440;
    }

    /** 只供已验证配置使用，整数分钟与数据库北京时间比较，不接受用户传入执行日期。 */
    public int startMinute() { return Integer.parseInt(dailyTime.substring(0, 2)) * 60 + Integer.parseInt(dailyTime.substring(3, 5)); }
}
