-- 保留计划 occurrence 首次检测到 Quartz misfire 的时刻。

ALTER TABLE batch.scheduled_occurrence ADD COLUMN misfire_detected_at TIMESTAMPTZ;

COMMENT ON COLUMN batch.scheduled_occurrence.misfire_detected_at IS
    '首次持久化记录 Quartz misfire 被发现的时刻；与恢复请求绑定后仍保留，API 启动调用方不得提供此值。';
