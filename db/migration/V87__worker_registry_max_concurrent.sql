-- ============================================================
-- V87: worker_registry 加 max_concurrent 列 + 反压索引
-- ============================================================
-- 背景: DefaultWorkerSelector 已用 current_load 做"挑负载最小者"排序,
-- 但缺"满了就 skip"的反压闸门。worker 满载后继续接 task → 排队上涨 → 心跳延迟 →
-- selector 还以为它能接(只看 current_load 排序不看上限) → 雪崩。
--
-- 修法: 加 max_concurrent 列(默认 10), DefaultWorkerSelector 加 current_load >= max_concurrent
-- 过滤;同 worker_group 全满时整个 group 退化为 WAITING(已有处理逻辑)。
--
-- 索引: 加 (tenant_id, worker_group, status, current_load) 让 selector findCandidates 走 idx
-- 而非全表扫(默认 10 行 worker 现状不影响,但生产 100+ worker 需要索引)
-- ============================================================

ALTER TABLE batch.worker_registry
    ADD COLUMN IF NOT EXISTS max_concurrent INTEGER NOT NULL DEFAULT 10;

ALTER TABLE batch.worker_registry
    ADD CONSTRAINT ck_worker_registry_max_concurrent CHECK (max_concurrent > 0);

CREATE INDEX IF NOT EXISTS idx_worker_registry_load
    ON batch.worker_registry (tenant_id, worker_group, status, current_load);

COMMENT ON COLUMN batch.worker_registry.max_concurrent IS
    'V87 (2026-05-03): worker 并发上限, current_load >= max_concurrent 时 selector skip 实现反压';
