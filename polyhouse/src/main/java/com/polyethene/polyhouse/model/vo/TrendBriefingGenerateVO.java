package com.polyethene.polyhouse.model.vo;

/**
 * 屋主同步触发 7 日风口简报生成的执行结果。
 * @param outcome 固定结果代号：GENERATED（新生成）、REUSED（同指纹零费用复用）、PENDING（已有在途/待核对请求）、
 *                BUDGET_LIMIT（月预算耗尽或熔断）、INVALID_OUTPUT（输出拒收但已按实结算）或 UNAVAILABLE（发送前不可用）
 * @param charged 本次调用是否实际触发了付费模型请求（结算或预留待核对）；零费用复用/拦截时为 false
 * @param briefing 执行结束后的最新简报只读视图（保留原有有效旧稿或最新有效稿）
 */
public record TrendBriefingGenerateVO(String outcome, boolean charged, TrendBriefingVO briefing) { }
