-- 018_trend_schedule_pause.sql
-- 在全局单槽控制表追加屋主人工急停开关（双层闸门：绝不越过服务端环境变量总闸）；
-- 仅在服务端总闸开启时允许屋主在发布台经二级确认暂停或恢复每日自动采集。
-- 执行前请先确认已完成 012_trend_jobs.sql，切勿盲目重复执行。
-- 逆序回退示例（仅删除 018 新增列，保留既有任务单槽、日报与费用事实）：
--   ALTER TABLE tool_trend_job_slot DROP COLUMN schedule_paused;

ALTER TABLE tool_trend_job_slot
    ADD COLUMN schedule_paused BOOLEAN NOT NULL DEFAULT FALSE
        COMMENT '屋主人工急停开关：TRUE 时阻断每日自动采集与自动简报，不越过服务端总闸';
