package com.polyethene.polyhouse.model.dto;

import jakarta.validation.constraints.*;

/**
 * 明确选择将预留额全额计入预算；不接受退款、平台实际用量或任意收费金额。
 * @param resolutionId 这一次处理意图的UUID，响应丢失必须复用
 * @param requestId 原AI请求UUID，不能传任务编号代替
 * @param expectedReservedMicros 从待核对列表看到的金额，防止对错记录或过期视图执行
 * @param confirmation 必须显式确认COUNT_RESERVED，提醒此操作不退回可用额度
 * @param reason 固定处理理由，不保存用户粘贴的Key或原始响应
 */
public record TrendFeeResolveDTO(
        @NotBlank @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String resolutionId,
        @NotBlank @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String requestId,
        @NotNull @Positive @Max(10000000) Long expectedReservedMicros,
        @NotBlank @Pattern(regexp = "COUNT_RESERVED") String confirmation,
        @NotBlank @Pattern(regexp = "PROVIDER_RECORD_CHECKED|RESULT_UNRECOVERABLE") String reason) { }
