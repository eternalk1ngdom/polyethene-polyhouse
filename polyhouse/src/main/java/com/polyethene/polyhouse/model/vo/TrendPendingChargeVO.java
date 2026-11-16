package com.polyethene.polyhouse.model.vo;

import java.time.Instant;

/**
 * 待核对列表仅供屋主；金额统一人民币微元，大整数身份以字符串返回。
 * @param requestId 应用内AI请求UUID，不承诺能自动匹配平台原始请求
 * @param repositoryId GitHub稳定编号字符串
 * @param state RESERVED或UNKNOWN，二者都不能自动退额或重发
 * @param reservedMicros 本笔仍持有的预算，关闭时全额计入已用
 * @param createdAt 原预留时间
 */
public record TrendPendingChargeVO(String requestId, String repositoryId, String state, long reservedMicros,
                                   Instant createdAt) { }
