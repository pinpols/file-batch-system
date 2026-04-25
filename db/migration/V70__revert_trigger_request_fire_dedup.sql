-- =========================================================
-- V70: 反向 V68 — 删除 trigger_request fire dedup 列 + UNIQUE INDEX
--
-- 移除原因(2026-04-26):
-- V68 加的 (trigger_runtime_state_id, scheduled_fire_time) 强约束本意是"R-1 重复 fire 兜底"。
-- 后发现 wheel.fire 的实际架构走 LaunchService.launchScheduled,后者内部 persistAndForward 也会
-- INSERT trigger_request — 形成"wheel 一行 + LaunchService 一行"双 INSERT,trigger_request
-- 表数据翻倍 + 审计混乱。
--
-- 重新审视 R-1 防御层次:
-- 1) marker CAS(claimForSchedule + version):同 trigger 同 next_fire_time 不可能两 leader claim
-- 2) LaunchService persistAndForward 软幂等:select-by-dedupKey 看到 existing → 不再 forward
-- 3) job_instance.uk_job_instance_tenant_dedup:业务侧最终兜底
--
-- 上述三层已完整覆盖 R-1,trigger_request 层 partial UNIQUE 是冗余设计。改为 wheel.fire 不再
-- 自己 INSERT trigger_request,直接调 LaunchService。本 migration 把 V68 加的字段+索引干净
-- 删除,避免留死代码 / 死索引。
--
-- 关联文档: docs/architecture/quartz-replacement-design.md §3 R-1 章节(已同步更新)
-- =========================================================

DROP INDEX IF EXISTS batch.uk_trigger_request_fire_dedup;
DROP INDEX IF EXISTS batch.idx_trigger_request_runtime_state;

ALTER TABLE batch.trigger_request
    DROP COLUMN IF EXISTS scheduled_fire_time,
    DROP COLUMN IF EXISTS trigger_runtime_state_id;
