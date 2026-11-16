-- 在010～013之后、任务停用时手动追加。定时身份独立于用户，不伪造OWNER账号。
ALTER TABLE tool_trend_job
    DROP CHECK ck_trend_job_actor,
    MODIFY actor_id BIGINT NULL,
    MODIFY credential_version BIGINT NULL,
    ADD CONSTRAINT ck_trend_job_actor_source CHECK (generation > 0 AND
        ((actor_id IS NULL AND credential_version IS NULL) OR
         (actor_id IS NOT NULL AND credential_version IS NOT NULL AND actor_id > 0 AND credential_version >= 0)));

CREATE TABLE tool_trend_schedule (
    period_date DATE NOT NULL COMMENT '受理时北京日期，绝不补造过去榜单',
    attempt TINYINT NOT NULL COMMENT '0首次，1唯一安全补偿',
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL COMMENT '数据库UTC受理时间',
    PRIMARY KEY (period_date,attempt),
    UNIQUE KEY uk_trend_schedule_job (job_id),
    CONSTRAINT ck_trend_schedule_attempt CHECK (attempt IN (0,1)),
    CONSTRAINT fk_trend_schedule_job FOREIGN KEY (job_id) REFERENCES tool_trend_job(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每日受理事实，随超过90天的终结任务清理';

-- 回退优先关闭定时、保留本表及身份快照；降级旧程序前关闭整个任务执行，公开报告可继续读取。
-- 不把NULL身份改成虚构OWNER。确需恢复012非空约束，先备份/导出定时任务及映射并获准处理相关行，
-- 确认不存在NULL身份后，再移除本表、恢复原约束/非空列。DDL不是整份脚本事务，不盲目重跑。
