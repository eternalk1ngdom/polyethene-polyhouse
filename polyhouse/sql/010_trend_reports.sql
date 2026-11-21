-- 仅在已确认的目标库追加，不执行 USE、不重建账号表，也不自动在应用启动时运行。
-- 两张表属于同一个迁移；DDL 非整体事务。失败时按运维文档核对部分建表，禁止盲目重跑。
CREATE TABLE tool_trend_report_version (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '幂等发布UUID',
    period_date DATE NOT NULL COMMENT '采集开始时的北京时间日期',
    captured_at DATETIME(6) NOT NULL COMMENT '实际采集开始时间UTC',
    payload JSON NOT NULL COMMENT '不含README正文的不可变公开报告',
    PRIMARY KEY (id),
    UNIQUE KEY uk_trend_version_day (period_date, id),
    KEY idx_trend_version_date (period_date, captured_at),
    CONSTRAINT chk_trend_report_object CHECK (JSON_TYPE(payload) = 'OBJECT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='技术雷达已发布历史版本';

CREATE TABLE tool_trend_daily (
    period_date DATE NOT NULL COMMENT '每个北京时间日期一个公开期号',
    report_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '当前已发布版本，空则不公开',
    PRIMARY KEY (period_date),
    UNIQUE KEY uk_trend_daily_report (report_id),
    -- 把日期也纳入外键，数据库拒绝把另一日版本挂到当前期号。
    CONSTRAINT fk_trend_daily_report FOREIGN KEY (period_date, report_id) REFERENCES tool_trend_report_version(period_date, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='技术雷达公开日报指针';
