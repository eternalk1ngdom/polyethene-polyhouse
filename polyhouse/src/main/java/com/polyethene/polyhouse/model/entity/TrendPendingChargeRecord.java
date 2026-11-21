package com.polyethene.polyhouse.model.entity;

import java.time.LocalDateTime;

/**
 * 只选取待核对查询需要的列，避免读取解读、README或平台响应。
 * @param id 原AI请求编号，不是任务编号或平台请求编号
 * @param repositoryId GitHub稳定编号
 * @param state RESERVED或UNKNOWN，不能据此判断平台是否已经计费
 * @param reservedMicros 目前仍占用的预算
 * @param createdAt 原预留的UTC时间
 */
public record TrendPendingChargeRecord(String id, long repositoryId, String state, long reservedMicros,
                                        LocalDateTime createdAt) { }
