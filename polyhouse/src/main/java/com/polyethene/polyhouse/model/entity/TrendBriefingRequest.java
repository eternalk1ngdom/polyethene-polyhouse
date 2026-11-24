package com.polyethene.polyhouse.model.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 7 日风口简报计费事实表投影；仅记录预算与指纹，不保存密钥、Prompt 或模型原文。
 * @param id 确定性幂等请求 UUID
 * @param monthKey 预留时的北京月份（YYYY-MM），跨月结算仍归该月
 * @param anchorDate 观察窗口锚点日期（北京时间）
 * @param sourceFingerprint 本次输入快照的 64 位 SHA-256 指纹
 * @param model 已核对计价的模型标识
 * @param ruleVersion 简报规则版本（briefing-1）
 * @param state RESERVED、UNKNOWN、SETTLED 或 CANCELLED
 * @param reservedMicros 本次预留持有的人民币微元上限
 * @param chargedMicros 已结算计入预算的微元，未终结时为 null
 * @param inputBound 保守输入 token 上界
 * @param outputBound 最大输出 token 上界
 * @param inputRate 每百万输入 token 人民币单价
 * @param outputRate 每百万输出 token 人民币单价
 */
public record TrendBriefingRequest(String id, String monthKey, LocalDate anchorDate, String sourceFingerprint,
                                   String model, String ruleVersion, String state, long reservedMicros,
                                   Long chargedMicros, int inputBound, int outputBound,
                                   BigDecimal inputRate, BigDecimal outputRate) { }
