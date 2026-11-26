-- 017_trend_ai_cache_tokens.sql
-- 在单仓与 7 日简报请求明细表记录 DeepSeek 缓存命中/未命中 Token 及参考实扣估算微元；
-- 仅用于屋主双口径对账与 Token 汇总，不修改 10 元月预算保守硬闸门。
-- 执行前请先确认已完成 011_trend_ai.sql 与 015_trend_briefings.sql，切勿盲目重复执行。

ALTER TABLE tool_trend_ai_request
    ADD COLUMN cache_hit_tokens INT NULL COMMENT '上游返回的 prompt_cache_hit_tokens，旧行或人工关闭为 NULL',
    ADD COLUMN cache_miss_tokens INT NULL COMMENT '上游返回的 prompt_cache_miss_tokens，旧行或人工关闭为 NULL',
    ADD COLUMN estimated_actual_micros BIGINT NULL COMMENT '按缓存命中与北京时间工作日高峰/空闲时段估算的参考实扣微元',
    ADD CONSTRAINT chk_trend_ai_req_cache_tokens CHECK (
        (cache_hit_tokens IS NULL OR cache_hit_tokens >= 0)
        AND (cache_miss_tokens IS NULL OR cache_miss_tokens >= 0)
        AND (estimated_actual_micros IS NULL OR estimated_actual_micros >= 0)
    );

ALTER TABLE tool_trend_briefing_request
    ADD COLUMN cache_hit_tokens INT NULL COMMENT '上游返回的 prompt_cache_hit_tokens，旧行或人工关闭为 NULL',
    ADD COLUMN cache_miss_tokens INT NULL COMMENT '上游返回的 prompt_cache_miss_tokens，旧行或人工关闭为 NULL',
    ADD COLUMN estimated_actual_micros BIGINT NULL COMMENT '按缓存命中与北京时间工作日高峰/空闲时段估算的参考实扣微元',
    ADD CONSTRAINT chk_trend_briefing_req_cache_tokens CHECK (
        (cache_hit_tokens IS NULL OR cache_hit_tokens >= 0)
        AND (cache_miss_tokens IS NULL OR cache_miss_tokens >= 0)
        AND (estimated_actual_micros IS NULL OR estimated_actual_micros >= 0)
    );
