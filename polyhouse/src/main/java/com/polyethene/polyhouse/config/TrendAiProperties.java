package com.polyethene.polyhouse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.math.BigDecimal;

/** AI 独立关闭；密钥不参与配置文本输出，也不通过校验异常回显拒绝值。 */
@ConfigurationProperties("polyhouse.trend.ai")
public record TrendAiProperties(boolean enabled, String apiKey, String model,
                                 BigDecimal inputYuanPerMillion, BigDecimal outputYuanPerMillion,
                                 Long monthlyBudgetMicros) {
    /** 初始按官方 Flash 高峰、未命中缓存计价；低峰与缓存优惠不用于扩大预算。 */
    public TrendAiProperties {
        model = model == null ? "deepseek-flash" : model;
        inputYuanPerMillion = inputYuanPerMillion == null ? new BigDecimal("2") : inputYuanPerMillion;
        outputYuanPerMillion = outputYuanPerMillion == null ? new BigDecimal("8") : outputYuanPerMillion;
        monthlyBudgetMicros = monthlyBudgetMicros == null ? 10_000_000L : monthlyBudgetMicros;
    }

    /** 只允许已核对计价的官方模型；错误配置停止付费而不阻止既有应用启动。 */
    public boolean ready() {
        return enabled && apiKey != null && apiKey.matches("[A-Za-z0-9_-]{16,256}")
                && "deepseek-flash".equals(model) && monthlyBudgetMicros > 0 && monthlyBudgetMicros <= 10_000_000L
                && inputYuanPerMillion.compareTo(new BigDecimal("2")) >= 0 && inputYuanPerMillion.compareTo(new BigDecimal("1000")) <= 0
                && outputYuanPerMillion.compareTo(new BigDecimal("8")) >= 0 && outputYuanPerMillion.compareTo(new BigDecimal("1000")) <= 0
                && inputYuanPerMillion.scale() <= 6 && outputYuanPerMillion.scale() <= 6;
    }

    /** 任何诊断只显示启用状态，不显示密钥、前后缀或其哈希。 */
    @Override public String toString() { return "TrendAiProperties[enabled=" + enabled + "]"; }
}
