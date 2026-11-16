package com.polyethene.polyhouse.model.entity;

/**
 * 余额与保守关闭汇总在一条SQL的一致性快照中读取，不依赖数据库默认隔离级别。
 * @param monthKey 原北京月份
 * @param usedMicros 可信估算和人工关闭已计入预算之和
 * @param heldMicros 仍预留的金额
 * @param suspended 用量违约后保持的暂停标记
 * @param conservativeMicros used中来自人工关闭的部分
 */
public record TrendFeeMonthRecord(String monthKey, long usedMicros, long heldMicros, boolean suspended,
                                   long conservativeMicros) { }
