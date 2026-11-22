-- =====================================================================
-- V104: trigger_runtime_state 本地计划审计字段
-- =====================================================================
-- 背景:
--   DST overlap 时同一本地 fire time 对应两个 UTC Instant; 仅靠
--   next_fire_time(UTC) 无法解释 "为什么这次 fire 比预期早/晚 1 小时"。
--   日批语义需要 (schedule_timezone, scheduled_local_date,
--   scheduled_local_time, fire_sequence) 作为本地视角的稳定 key,
--   配合 cron 计算路径回写,方便排障与去重。
--
-- 字段:
--   schedule_timezone    cron 解释时使用的 IANA ZoneId(快照, 不重命名)
--   scheduled_local_date next_fire_time 在 schedule_timezone 下的 LocalDate
--   scheduled_local_time next_fire_time 在 schedule_timezone 下的 LocalTime
--   fire_sequence        同一 (scheduled_local_date, scheduled_local_time)
--                        连续触发计数 — DST overlap 时 == 2, 其余 == 1
--
-- 兼容:
--   全部允许 NULL(老行回填由 reconciler 自然推进, 不强制 backfill)。
--   fire_sequence 默认 1。
-- =====================================================================

ALTER TABLE batch.trigger_runtime_state
    ADD COLUMN IF NOT EXISTS schedule_timezone    VARCHAR(64),
    ADD COLUMN IF NOT EXISTS scheduled_local_date DATE,
    ADD COLUMN IF NOT EXISTS scheduled_local_time TIME,
    ADD COLUMN IF NOT EXISTS fire_sequence        INTEGER NOT NULL DEFAULT 1;

COMMENT ON COLUMN batch.trigger_runtime_state.schedule_timezone IS
    'IANA ZoneId 快照, cron 计算 next_fire_time 时使用的时区';
COMMENT ON COLUMN batch.trigger_runtime_state.scheduled_local_date IS
    'next_fire_time 在 schedule_timezone 下的 LocalDate';
COMMENT ON COLUMN batch.trigger_runtime_state.scheduled_local_time IS
    'next_fire_time 在 schedule_timezone 下的 LocalTime';
COMMENT ON COLUMN batch.trigger_runtime_state.fire_sequence IS
    '同一本地计划时间的连续 fire 计数; DST overlap 第二次触发会 ++';
