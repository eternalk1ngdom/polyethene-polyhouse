package com.polyethene.polyhouse.model.vo;

import java.time.LocalDate;
import java.util.List;

/**
 * 星潮发布台「每日自动采集状态」只读视图，面向屋主显性展示自动化开关、今日受理状态与最近 5 条任务记录。
 * @param moduleEnabled 星潮模块总开关
 * @param jobsEnabled 任务执行器开关
 * @param scheduleEnabled 每日自动日报开关是否就绪（开启且时间参数合法）
 * @param briefingAutoEnabled 每日自动日报发布成功后是否顺路自动生成 7 日风口简报
 * @param aiReady AI 解读与简报模型配置是否就绪
 * @param dailyTime 每日计划采集时间（北京时间 HH:mm）
 * @param retryDelayMinutes 首次采集失败后的补偿延迟分钟数
 * @param todayDate 数据库时钟换算的当前北京日期
 * @param todayStatus 今日自动受理状态码：DISABLED / PAUSED / WAITING_WINDOW / RUNNING / PUBLISHED_AUTO / PUBLISHED_MANUAL / RETRY_PENDING / FAILED / MISSED_WINDOW
 * @param todayPublished 今日是否已有已发布日报
 * @param recentJobs 最近 5 条任务记录（区分 SCHEDULED 与 MANUAL）
 * @param schedulePaused 是否已被屋主人工急停暂停（仅在服务端总闸开启时生效）
 */
public record TrendScheduleStatusVO(
        boolean moduleEnabled,
        boolean jobsEnabled,
        boolean scheduleEnabled,
        boolean briefingAutoEnabled,
        boolean aiReady,
        String dailyTime,
        int retryDelayMinutes,
        LocalDate todayDate,
        String todayStatus,
        boolean todayPublished,
        List<TrendJobVO> recentJobs,
        boolean schedulePaused) {
    /** 冻结最近任务列表，防止调用方修改响应视图。 */
    public TrendScheduleStatusVO {
        recentJobs = recentJobs == null ? List.of() : List.copyOf(recentJobs);
    }

    /** 兼容旧 11 参构造：默认未处于人工急停状态。 */
    public TrendScheduleStatusVO(
            boolean moduleEnabled,
            boolean jobsEnabled,
            boolean scheduleEnabled,
            boolean briefingAutoEnabled,
            boolean aiReady,
            String dailyTime,
            int retryDelayMinutes,
            LocalDate todayDate,
            String todayStatus,
            boolean todayPublished,
            List<TrendJobVO> recentJobs) {
        this(moduleEnabled, jobsEnabled, scheduleEnabled, briefingAutoEnabled, aiReady, dailyTime,
                retryDelayMinutes, todayDate, todayStatus, todayPublished, recentJobs, false);
    }
}
