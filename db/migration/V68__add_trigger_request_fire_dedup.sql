-- =========================================================
-- V68: trigger_request 加 fire 强约束 — 防双 leader 重复 fire
--
-- 风险来源(design.md §3.1):
--   GC pause / ShedLock 锁过期 / 网络分区 → 两个 leader 都 fire 同一 trigger 同一时刻
--   单纯 LaunchService 应用层幂等(dedup_key 天级粒度)防不住高频 cron
--
-- 兜底机制:
--   trigger_request 加 (trigger_runtime_state_id, scheduled_fire_time) 唯一约束
--   leader fire 前 INSERT,撞键 → DuplicateKeyException → 跳过本次 fire
--
-- 关联文档: docs/architecture/quartz-replacement-design.md §3
-- =========================================================

ALTER TABLE batch.trigger_request
    ADD COLUMN IF NOT EXISTS scheduled_fire_time      TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS trigger_runtime_state_id BIGINT;

-- 软外键(不强制,因为 runtime_state CASCADE 删除时 trigger_request 历史记录应保留)
COMMENT ON COLUMN batch.trigger_request.scheduled_fire_time IS
    '时间轮 fire 时设置的预定 fire 时刻;非时间轮路径(API/MANUAL/EVENT)为 NULL';
COMMENT ON COLUMN batch.trigger_request.trigger_runtime_state_id IS
    '关联 trigger_runtime_state.id;非时间轮路径为 NULL';

-- 关键唯一约束(partial index):同一 runtime_state + 同一 scheduled_fire_time 全局唯一
-- 用 partial index 是因为只对时间轮 fire 出来的记录生效,
-- 历史 API/MANUAL/EVENT trigger_request(两列都为 NULL)不受影响
CREATE UNIQUE INDEX IF NOT EXISTS uk_trigger_request_fire_dedup
    ON batch.trigger_request (trigger_runtime_state_id, scheduled_fire_time)
    WHERE trigger_runtime_state_id IS NOT NULL
      AND scheduled_fire_time IS NOT NULL;

-- 顺带修复:dedup_key 在高频 cron 场景的天级粒度撞键 bug
-- 现状:dedup_key 通常是 tenant_id:job_code:biz_date,天级,
--       高频 cron(每 5 分钟)一天 288 次 fire 都用同 dedup_key → 后 287 次报 DUPLICATE
-- 修法:时间轮 fire 路径的 dedup_key 改为 tenant_id:job_code:scheduled_fire_time_epoch_ms
--       (Java 侧 buildDedupKey 实现,见 design.md §3.5)
-- 本 migration 不强制改老数据,只是给新路径用新 key 格式

-- 索引:运维侧"查某 trigger_runtime_state 的所有历史 fire"
CREATE INDEX IF NOT EXISTS idx_trigger_request_runtime_state
    ON batch.trigger_request (trigger_runtime_state_id, scheduled_fire_time DESC)
    WHERE trigger_runtime_state_id IS NOT NULL;
