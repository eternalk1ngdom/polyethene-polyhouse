-- 在 010～014 之后手动追加，不执行 USE、不修改账号及既有日报/单仓账本，也不在应用启动时自动运行。
-- 三张表属于同一迁移，DDL 非整体事务；共享 tool_trend_ai_month 十元月预算与熔断开关。
-- 统一锁序：tool_trend_briefing(anchor_date) -> tool_trend_ai_month(month_key) -> tool_trend_briefing_request(id)。

CREATE TABLE tool_trend_briefing (
    anchor_date DATE NOT NULL COMMENT '观察窗口锚点日期（北京时间）',
    window_start DATE NULL COMMENT '最近有效稿覆盖窗口起始日',
    window_end DATE NULL COMMENT '最近有效稿覆盖窗口结束日（等于anchor_date）',
    covered_days TINYINT NULL COMMENT '最近有效稿实际覆盖天数（3至7）',
    sample_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '最近有效稿样本档位：LIMITED或SUFFICIENT',
    source_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '最近有效稿输入快照SHA-256指纹',
    model VARCHAR(32) NULL COMMENT '最近有效稿使用的模型标识',
    rule_version VARCHAR(32) NULL COMMENT '最近有效稿使用的规则版本',
    active_request CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '未结束付费请求的执行槽',
    payload JSON NULL COMMENT '最近有效脱敏简报正文，不含原始Prompt',
    generated_at DATETIME(6) NULL COMMENT '最近有效稿生成时间UTC',
    PRIMARY KEY (anchor_date),
    CONSTRAINT chk_trend_briefing CHECK (
        (payload IS NULL AND window_start IS NULL AND window_end IS NULL AND covered_days IS NULL
            AND sample_status IS NULL AND source_fingerprint IS NULL AND model IS NULL
            AND rule_version IS NULL AND generated_at IS NULL)
        OR (payload IS NOT NULL AND JSON_TYPE(payload) = 'OBJECT'
            AND window_start IS NOT NULL AND window_end = anchor_date AND window_start <= window_end
            AND covered_days BETWEEN 3 AND 7
            AND sample_status IN ('LIMITED', 'SUFFICIENT')
            AND source_fingerprint IS NOT NULL AND model IS NOT NULL
            AND rule_version IS NOT NULL AND generated_at IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='星潮7日风口简报有效稿与并发执行槽';

CREATE TABLE tool_trend_briefing_request (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '确定性幂等请求UUID',
    month_key CHAR(7) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '北京月份YYYY-MM',
    anchor_date DATE NOT NULL COMMENT '观察窗口锚点日期（北京时间）',
    source_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '本次输入快照SHA-256指纹',
    model VARCHAR(32) NOT NULL COMMENT '模型标识',
    rule_version VARCHAR(32) NOT NULL COMMENT '规则版本',
    state VARCHAR(16) NOT NULL COMMENT 'RESERVED/UNKNOWN/SETTLED/CANCELLED',
    reserved_micros BIGINT NOT NULL COMMENT '预留微元上限',
    charged_micros BIGINT NULL COMMENT '最终结算微元',
    input_bound INT NOT NULL COMMENT '保守输入token上界',
    output_bound INT NOT NULL COMMENT '最大输出token上界',
    input_rate DECIMAL(12,6) NOT NULL COMMENT '人民币每百万输入token',
    output_rate DECIMAL(12,6) NOT NULL COMMENT '人民币每百万输出token',
    prompt_tokens INT NULL COMMENT '上游返回输入token',
    completion_tokens INT NULL COMMENT '上游返回输出token',
    outcome VARCHAR(40) NULL COMMENT '固定原因代号，不存原始错误',
    created_at DATETIME(6) NOT NULL COMMENT '预留时间UTC',
    PRIMARY KEY (id),
    KEY idx_trend_briefing_request_month (month_key, state),
    KEY idx_trend_briefing_request_anchor (anchor_date, created_at),
    CONSTRAINT fk_trend_briefing_request_month FOREIGN KEY (month_key) REFERENCES tool_trend_ai_month(month_key),
    CONSTRAINT fk_trend_briefing_request_anchor FOREIGN KEY (anchor_date) REFERENCES tool_trend_briefing(anchor_date),
    CONSTRAINT chk_trend_briefing_request CHECK (
        state IN ('RESERVED', 'UNKNOWN', 'SETTLED', 'CANCELLED')
        AND reserved_micros > 0 AND reserved_micros <= 10000000
        AND (charged_micros IS NULL OR charged_micros BETWEEN 0 AND reserved_micros)
        AND input_bound > 0 AND output_bound > 0 AND input_rate > 0 AND output_rate > 0
        AND ((state IN ('RESERVED', 'UNKNOWN') AND charged_micros IS NULL)
            OR (state IN ('SETTLED', 'CANCELLED') AND charged_micros IS NOT NULL))
        AND (state <> 'CANCELLED' OR charged_micros = 0)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='星潮7日风口简报付费事实，不随超时自动重发';

CREATE TABLE tool_trend_briefing_resolution (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '屋主本次处理意图UUID',
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原简报AI请求UUID',
    actor_id BIGINT NOT NULL COMMENT '当时已核验的屋主编号快照，不随账号变化改写',
    month_key CHAR(7) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原预留北京月份YYYY-MM',
    accounted_micros BIGINT NOT NULL COMMENT '将原预留上限计入预算，不代表平台实际扣费',
    reason VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '固定原因，不接收自由文本',
    completed_at DATETIME(6) NOT NULL COMMENT '数据库UTC时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_trend_briefing_resolution_request (request_id),
    KEY idx_trend_briefing_resolution_month (month_key),
    CONSTRAINT fk_trend_briefing_resolution_request FOREIGN KEY (request_id) REFERENCES tool_trend_briefing_request(id),
    CONSTRAINT fk_trend_briefing_resolution_month FOREIGN KEY (month_key) REFERENCES tool_trend_ai_month(month_key),
    CONSTRAINT chk_trend_briefing_resolution CHECK (
        actor_id > 0 AND accounted_micros BETWEEN 1 AND 10000000
        AND reason IN ('PROVIDER_RECORD_CHECKED', 'RESULT_UNRECOVERABLE')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='星潮7日风口简报保守预算关闭审计，不修改平台账单';

-- 回退：优先停用简报生成与读取入口并暂留本批表。确需移除须先备份并确认无未结清预留与审计依赖，
-- 再按外键逆序执行 DROP TABLE：tool_trend_briefing_resolution -> tool_trend_briefing_request -> tool_trend_briefing。
-- 不能把回退解释为退费：tool_trend_ai_month 中已计入的 used_micros 与 suspended 状态必须保留。
