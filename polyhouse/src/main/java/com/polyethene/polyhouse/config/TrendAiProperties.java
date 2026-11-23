package com.polyethene.polyhouse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import java.math.BigDecimal;

/** AI 独立关闭；密钥不参与配置文本输出，也不通过校验异常回显拒绝值。 */
@ConfigurationProperties("polyhouse.trend.ai")
public record TrendAiProperties(boolean enabled, String apiKey, String model,
                                 BigDecimal inputYuanPerMillion, BigDecimal outputYuanPerMillion,
                                 Long monthlyBudgetMicros, Boolean thinkingEnabled, String thinkingEffort) {
    /** 关闭思考模式时的默认最大输出 token 上限。 */
    public static final int STANDARD_MAX_TOKENS = 1500;
    /** 启用轻度思考（low）时的最大输出 token 上限（含推理与最终 JSON）。 */
    public static final int THINKING_MAX_TOKENS = 3000;
    /** 关闭思考模式时的单次上游 HTTP 超时（秒）。 */
    public static final int STANDARD_TIMEOUT_SECONDS = 30;
    /** 启用轻度思考（low）时的单次上游 HTTP 超时（秒）。 */
    public static final int THINKING_TIMEOUT_SECONDS = 60;

    /** 初始按官方 Flash 高峰、未命中缓存计价；轻度思考默认关闭，低峰与缓存优惠不用于扩大预算。 */
    @ConstructorBinding
    public TrendAiProperties {
        model = model == null ? "deepseek-flash" : model;
        inputYuanPerMillion = inputYuanPerMillion == null ? new BigDecimal("2") : inputYuanPerMillion;
        outputYuanPerMillion = outputYuanPerMillion == null ? new BigDecimal("8") : outputYuanPerMillion;
        monthlyBudgetMicros = monthlyBudgetMicros == null ? 10_000_000L : monthlyBudgetMicros;
        thinkingEnabled = thinkingEnabled != null && thinkingEnabled;
        thinkingEffort = thinkingEffort == null ? "low" : thinkingEffort;
    }

    /** 兼容旧 6 参数构造调用，默认关闭思考模式。 */
    public TrendAiProperties(boolean enabled, String apiKey, String model,
                             BigDecimal inputYuanPerMillion, BigDecimal outputYuanPerMillion,
                             Long monthlyBudgetMicros) {
        this(enabled, apiKey, model, inputYuanPerMillion, outputYuanPerMillion, monthlyBudgetMicros, false, "low");
    }

    /** 是否显式启用了轻度思考模式。 */
    public boolean thinkingActive() {
        return Boolean.TRUE.equals(thinkingEnabled);
    }

    /** 按是否启用轻度思考返回本次允许的最大输出 token 上界。 */
    public int maxOutputTokens() {
        return thinkingActive() ? THINKING_MAX_TOKENS : STANDARD_MAX_TOKENS;
    }

    /** 按是否启用轻度思考返回单次上游请求的超时秒数。 */
    public int requestTimeoutSeconds() {
        return thinkingActive() ? THINKING_TIMEOUT_SECONDS : STANDARD_TIMEOUT_SECONDS;
    }

    /** 只允许已核对计价的官方模型与低档思考；错误配置停止付费而不阻止既有应用启动。 */
    public boolean ready() {
        return enabled && apiKey != null && apiKey.matches("[A-Za-z0-9_-]{16,256}")
                && "deepseek-flash".equals(model) && monthlyBudgetMicros > 0 && monthlyBudgetMicros <= 10_000_000L
                && inputYuanPerMillion.compareTo(new BigDecimal("2")) >= 0 && inputYuanPerMillion.compareTo(new BigDecimal("1000")) <= 0
                && outputYuanPerMillion.compareTo(new BigDecimal("8")) >= 0 && outputYuanPerMillion.compareTo(new BigDecimal("1000")) <= 0
                && inputYuanPerMillion.scale() <= 6 && outputYuanPerMillion.scale() <= 6
                && "low".equals(thinkingEffort);
    }

    /** 任何诊断只显示开关状态，不显示密钥、前后缀或其哈希。 */
    @Override public String toString() {
        return "TrendAiProperties[enabled=" + enabled + ", thinkingEnabled=" + thinkingEnabled + "]";
    }
}
