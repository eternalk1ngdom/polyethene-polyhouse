package com.polyethene.polyhouse.model.entity;

/**
 * 月总额是所有自动、手动及真实测试共用的持久边界。
 * @param monthKey 北京月份
 * @param usedMicros 已结算的人民币微元
 * @param heldMicros 未结算及结果不明的预留额
 * @param suspended 发现上游用量超约定上界时停止该月新请求
 */
public record TrendAiMonth(String monthKey, long usedMicros, long heldMicros, boolean suspended) { }
