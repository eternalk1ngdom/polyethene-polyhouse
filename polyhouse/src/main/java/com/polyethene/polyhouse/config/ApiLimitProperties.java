package com.polyethene.polyhouse.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 入口额度只在生产绑定，错误值在启动时拒绝，避免上线后静默失去保护。 */
@Validated
@ConfigurationProperties("polyhouse.api-limits")
record ApiLimitProperties(@NotNull @Valid Budget api, @NotNull @Valid Budget login,
                          @NotNull @Valid Budget sensitive) {

    record Budget(@Min(1) @Max(60_000) int requestsPerMinute, @Min(1) @Max(1_000) int burst,
                  @Min(1) @Max(64) int concurrent) {
    }
}
