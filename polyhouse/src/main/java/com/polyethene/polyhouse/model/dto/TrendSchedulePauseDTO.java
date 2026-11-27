package com.polyethene.polyhouse.model.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 屋主人工暂停或恢复每日自动采集的请求体；仅控制人工急停标志，不能开启未启用的服务端总闸。
 * @param paused true 表示立即暂停后续每日自动采集，false 表示恢复后续窗口自动采集
 */
public record TrendSchedulePauseDTO(@NotNull(message = "请明确指定暂停或恢复状态") Boolean paused) { }
