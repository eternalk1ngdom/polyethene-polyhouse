package com.polyethene.polyhouse.model.entity;

import java.time.Instant;

/**
 * 单次采集可核对的故障，只保留有限值，不携带请求、响应正文或令牌。
 * @param stage 实际失败或保护性跳过的环节
 * @param reason 已知原因，无法细分时保守记录
 * @param status 收到的 HTTP 状态，无响应或未请求时为空
 * @param retryAt 限流等待截止时间，未知或不适用时为空
 */
public record TrendSourceIssue(Stage stage, Reason reason, Integer status, Instant retryAt) {
    /** 环节用于区分丢失身份、正文或仅缺分类依据。 */
    public enum Stage {
        /** 基础榜单不可用时不能发布。 */ LISTING,
        /** 元数据用于确认公开身份。 */ METADATA,
        /** 正文和内容版本共同决定新分析资格。 */ README,
        /** 根目录只为分类提供证据。 */ DIRECTORY
    }

    /** 保留真实原因，避免将所有 403 解释成限流。 */
    public enum Reason {
        /** 有明确限流状态或响应头。 */ RATE_LIMITED,
        /** 收到拒绝或认证错误，具体成因未明。 */ ACCESS_REFUSED,
        /** GitHub 明确拒绝当前凭据读取这项资源。 */ RESOURCE_ACCESS_REFUSED,
        /** 上游服务端错误。 */ UPSTREAM_ERROR,
        /** 无法取得可用响应。 */ TRANSPORT_FAILURE,
        /** 仓库或材料请求返回其他错误。 */ HTTP_ERROR,
        /** 明确未取得 README 文件。 */ README_NOT_FOUND,
        /** 格式、大小或跳转不符合约束。 */ INVALID_RESPONSE,
        /** 不能用私有仓库资料降级。 */ PRIVATE_REPOSITORY,
        /** 榜单与元数据没有指向同一仓库。 */ IDENTITY_CONFLICT,
        /** 本轮暂停后没有尝试这个仓库。 */ SKIPPED_AFTER_PAUSE,
        /** 之前收到的等待期限尚未结束。 */ COOLDOWN
    }
}
