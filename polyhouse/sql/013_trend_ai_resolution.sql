-- 在010/011/012之后追加，不自动运行；只保存保守关闭事实，不保存密钥或平台原始响应。
CREATE TABLE tool_trend_ai_resolution (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '屋主本次处理意图UUID',
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id BIGINT NOT NULL COMMENT '当时已核验的屋主编号快照，不随账号变化改写',
    month_key CHAR(7) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    accounted_micros BIGINT NOT NULL COMMENT '将原预留上限计入预算，不代表平台实际扣费',
    reason VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '固定原因，不接收自由文本',
    completed_at DATETIME(6) NOT NULL COMMENT '数据库UTC时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_trend_ai_resolution_request (request_id),
    KEY idx_trend_ai_resolution_month (month_key),
    CONSTRAINT fk_trend_ai_resolution_request FOREIGN KEY (request_id) REFERENCES tool_trend_ai_request(id),
    CONSTRAINT fk_trend_ai_resolution_month FOREIGN KEY (month_key) REFERENCES tool_trend_ai_month(month_key),
    CONSTRAINT ck_trend_ai_resolution CHECK (actor_id > 0 AND accounted_micros BETWEEN 1 AND 10000000
        AND reason IN ('PROVIDER_RECORD_CHECKED','RESULT_UNRECOVERABLE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='雷达AI保守预算关闭审计，不修改平台账单';

-- 回退优先停用接口并暂留本表。确需移除须先备份及确认本表处理事实仍可恢复，再DROP本表。
-- 不能把回退解释为退费：011的SETTLED/MANUAL_RESERVED及已用金额必须保留，不得恢复原预留或重发。
