package com.polyethene.polyhouse.model.dto;
import jakarta.validation.constraints.*;
/** 针对单份待审版本做决定，不能提交昵称/图片替代实际申请内容。
 * @param expectedVersion 页面确认的申请版本
 * @param approve true 通过，false 驳回
 * @param reason 最小必要说明，不记录凭据或隐私
 */
public record ProfileDecisionDTO(@NotNull @Positive Long expectedVersion, @NotNull Boolean approve,
        @NotBlank @Size(max=200) String reason) { }
