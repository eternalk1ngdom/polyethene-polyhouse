-- 追加 G2 三表；不自动运行，不修改账号和既有日报。真实库先备份、确认选中库及两次迁移状态。
CREATE TABLE tool_trend_ai_month (
    month_key CHAR(7) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY COMMENT '北京月份YYYY-MM',
    used_micros BIGINT NOT NULL DEFAULT 0 COMMENT '已结算微元',
    held_micros BIGINT NOT NULL DEFAULT 0 COMMENT '预留及待核对微元',
    suspended BOOLEAN NOT NULL DEFAULT FALSE COMMENT '用量异常时停止本月新请求',
    CONSTRAINT chk_trend_ai_month CHECK (used_micros >= 0 AND held_micros >= 0 AND used_micros + held_micros <= 10000000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='雷达AI十元月预算';

CREATE TABLE tool_trend_ai_repository (
    repository_id BIGINT PRIMARY KEY COMMENT 'GitHub稳定编号',
    active_request CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '未结束付费请求的执行槽',
    payload JSON NULL COMMENT '最近成功解读，不含README正文',
    CONSTRAINT chk_trend_ai_repository CHECK (repository_id > 0 AND (payload IS NULL OR JSON_TYPE(payload) = 'OBJECT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='雷达AI仓库缓存和执行槽';

CREATE TABLE tool_trend_ai_request (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY COMMENT '确定性幂等请求UUID',
    month_key CHAR(7) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    repository_id BIGINT NOT NULL,
    readme_sha VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    model VARCHAR(32) NOT NULL,
    rule_version VARCHAR(32) NOT NULL,
    state VARCHAR(16) NOT NULL COMMENT 'RESERVED/UNKNOWN/SETTLED/CANCELLED',
    reserved_micros BIGINT NOT NULL,
    charged_micros BIGINT NULL,
    input_bound INT NOT NULL,
    output_bound INT NOT NULL,
    input_rate DECIMAL(12,6) NOT NULL COMMENT '人民币每百万token',
    output_rate DECIMAL(12,6) NOT NULL,
    prompt_tokens INT NULL,
    completion_tokens INT NULL,
    outcome VARCHAR(40) NULL COMMENT '固定原因代号，不存原始错误',
    created_at DATETIME(6) NOT NULL COMMENT '预留时间UTC',
    KEY idx_trend_ai_request_month (month_key, state),
    CONSTRAINT fk_trend_ai_request_month FOREIGN KEY (month_key) REFERENCES tool_trend_ai_month(month_key),
    CONSTRAINT fk_trend_ai_request_repo FOREIGN KEY (repository_id) REFERENCES tool_trend_ai_repository(repository_id),
    CONSTRAINT chk_trend_ai_request CHECK (state IN ('RESERVED','UNKNOWN','SETTLED','CANCELLED')
        AND reserved_micros > 0 AND reserved_micros <= 10000000
        AND (charged_micros IS NULL OR charged_micros BETWEEN 0 AND reserved_micros)
        AND input_bound > 0 AND output_bound > 0 AND input_rate > 0 AND output_rate > 0
        AND ((state IN ('RESERVED','UNKNOWN') AND charged_micros IS NULL)
            OR (state IN ('SETTLED','CANCELLED') AND charged_micros IS NOT NULL))
        AND (state <> 'CANCELLED' OR charged_micros = 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='雷达AI付费事实，不随超时自动重发';
