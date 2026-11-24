package com.polyethene.polyhouse.model.entity;

import java.time.LocalDateTime;

/**
 * 只选取待核对查询需要的列，避免读取解读、README或平台响应；同时承载单仓与 7 日风口简报待核对请求。
 * @param id 原AI请求编号，不是任务编号或平台请求编号
 * @param repositoryId 单仓请求为 GitHub 稳定编号字符串；简报请求为 briefing:YYYY-MM-DD 标识
 * @param chargeKind 计费来源类型：REPO（单仓解读）或 BRIEFING（7 日风口简报）
 * @param state RESERVED或UNKNOWN，不能据此判断平台是否已经计费
 * @param reservedMicros 目前仍占用的预算
 * @param createdAt 原预留的UTC时间
 */
public record TrendPendingChargeRecord(String id, String repositoryId, String chargeKind, String state,
                                        long reservedMicros, LocalDateTime createdAt) { }
