package com.polyethene.polyhouse.model.entity;

import java.math.BigDecimal;

/**
 * 只记录计费事实及输入指纹，不保存请求头、密钥、Prompt 或原始响应。
 * @param id 同一任务与仓库的确定性请求 UUID
 * @param monthKey 预留时的北京月份，跨月结算仍归该月
 * @param repositoryId GitHub 编号
 * @param readmeSha 分析资料 SHA
 * @param model 已核对计价模型
 * @param ruleVersion 解读规则版本
 * @param state RESERVED、UNKNOWN、SETTLED 或 CANCELLED
 * @param reservedMicros 本次持有的人民币微元
 * @param chargedMicros 已确认的保守计费，未知为 null
 * @param inputBound 输入 token 保守上界
 * @param outputBound 已发送的最大输出 token
 * @param inputRate 每百万输入 token 的人民币价格
 * @param outputRate 每百万输出 token 的人民币价格
 */
public record TrendAiRequest(String id, String monthKey, long repositoryId, String readmeSha, String model,
                              String ruleVersion, String state, long reservedMicros, Long chargedMicros,
                              int inputBound, int outputBound, BigDecimal inputRate, BigDecimal outputRate) { }
