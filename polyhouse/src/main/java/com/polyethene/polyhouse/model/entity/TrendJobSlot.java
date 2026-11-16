package com.polyethene.polyhouse.model.entity;

import java.time.LocalDateTime;

/**
 * 只允许一条固定行，锁该行后再判断任务所有权。
 * @param activeId 当前已受理的唯一任务，空闲时为 null
 * @param generation 单调执行代数，不能在清理任务时归零
 * @param lastAcceptedAt 全实例共用的冷却起点
 */
public record TrendJobSlot(String activeId, long generation, LocalDateTime lastAcceptedAt) { }
