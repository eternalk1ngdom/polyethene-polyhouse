-- 在 010/011 之后手动追加；默认配置不运行迁移，也不启动任务。
CREATE TABLE tool_trend_job (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id BIGINT NOT NULL,
    credential_version BIGINT NOT NULL,
    ai_requested BOOLEAN NOT NULL,
    generation BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    phase VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    started_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    lease_until DATETIME(6) NULL,
    deadline DATETIME(6) NOT NULL,
    report_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    reason VARCHAR(32) NULL,
    PRIMARY KEY (id),
    KEY idx_trend_job_retention (state, completed_at),
    CONSTRAINT ck_trend_job_actor CHECK (actor_id > 0 AND credential_version >= 0 AND generation > 0),
    CONSTRAINT ck_trend_job_state CHECK (state IN ('QUEUED','RUNNING','SUCCEEDED','PARTIAL','FAILED','INTERRUPTED')),
    CONSTRAINT ck_trend_job_phase CHECK (phase IN ('QUEUED','COLLECTING','ANALYZING','PUBLISHING','DONE')),
    CONSTRAINT ck_trend_job_completion CHECK (
        (state IN ('QUEUED','RUNNING') AND completed_at IS NULL AND report_id IS NULL)
        OR (state IN ('SUCCEEDED','PARTIAL') AND completed_at IS NOT NULL AND report_id IS NOT NULL)
        OR (state IN ('FAILED','INTERRUPTED') AND completed_at IS NOT NULL AND report_id IS NULL)
    ),
    CONSTRAINT fk_trend_job_report FOREIGN KEY (report_id) REFERENCES tool_trend_report_version(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='技术雷达有界任务事实；不保存登录令牌或源材料';

CREATE TABLE tool_trend_job_slot (
    singleton_id TINYINT NOT NULL,
    active_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    generation BIGINT NOT NULL DEFAULT 0,
    last_accepted_at DATETIME(6) NULL,
    PRIMARY KEY (singleton_id),
    CONSTRAINT ck_trend_job_singleton CHECK (singleton_id = 1 AND generation >= 0),
    CONSTRAINT fk_trend_job_active FOREIGN KEY (active_id) REFERENCES tool_trend_job(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据库单槽及执行代数；不是可失效的Redis防重锁';

INSERT INTO tool_trend_job_slot(singleton_id) VALUES (1);
-- 回退：停用任务并等待外部请求结束，确认没有待执行任务后，先 DROP tool_trend_job_slot，后 DROP tool_trend_job。
-- 010/011 的报告及费用事实不随任务回退删除。
