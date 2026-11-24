package com.polyethene.polyhouse.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** @param coverKey 所选款式，不接收用户身份或客户端金额
 * @param quoteVersion 用户确认时看到的报价版本 */
public record CoverPurchaseDTO(@NotBlank @Size(max=24) String coverKey,
                               @NotBlank @Size(max=16) String quoteVersion) {}
