-- V175: outbox/event 簇 4 张表复合 PK 化（Citus distributed 前置）
--
-- 目的：将 outbox/event 簇 4 张表的 PRIMARY KEY 改为含 tenant_id 的复合形式，
--       这是 Citus distributed table 的前置条件（分片键必须在 PK 中）。
-- 参见：docs/analysis/citus-poc-gates-2026-06-11.md
--
-- === 量测结果 ===
--
--   · outbox_event（分区父表）
--       当前 PK：outbox_event_p_pkey (id, created_at)（V170 建分区父表时命名）
--       目标：(tenant_id, id, created_at)（分区表 PK 必须含分区键 created_at）
--       UNIQUE uk_outbox_event_key (tenant_id, event_key, created_at) — 保持不动
--
--   · event_delivery_log
--       当前 PK：event_delivery_log_pkey (id)
--       目标：(tenant_id, id)
--       入站 FK event_delivery_log_outbox_event_id_fkey → outbox_event：
--           V170 已 DROP（outbox_event 是分区表，PG 不支持跨分区表 FK，应用层守护）
--           本迁移不重建。
--
--   · event_outbox_retry
--       当前 PK：event_outbox_retry_pkey (id)
--       目标：(tenant_id, id)
--       入站 FK event_outbox_retry_outbox_event_id_fkey → outbox_event：
--           V170 已 DROP，同上，不重建。
--
--   · worker_report_outbox
--       当前 PK：worker_report_outbox_pkey (id)
--       目标：(tenant_id, id)
--       无入站/出站 FK。
--
-- === mapper 缺口决策 ===
--
--   WorkerReportOutboxPgMapper 中 claimNextReturning 后的跟进操作
--   (selectAttemptCount / updateGiveUp / updateRetry / deleteById / giveUpRow)
--   均按 claim 返回的 id 单列操作（id 在 claim 时已唯一确定）。
--   Citus 启用时需补 (tenant_id, id) 成对 WHERE；当前保持现状，记录为"Citus 启用时再改"。
--
-- 禁 psql 元命令；禁 BEGIN/COMMIT（Flyway 管事务）

-- ============================================================
-- 1. outbox_event（分区父表）
--    当前 PK：outbox_event_p_pkey (id, created_at)  目标：(tenant_id, id, created_at)
--    注：分区父表 PK 必须包含分区键 created_at；约束名 outbox_event_p_pkey 沿用（建表期名）
-- ============================================================

ALTER TABLE batch.outbox_event DROP CONSTRAINT outbox_event_p_pkey;
ALTER TABLE batch.outbox_event ADD CONSTRAINT outbox_event_p_pkey
    PRIMARY KEY (tenant_id, id, created_at);

-- ============================================================
-- 2. event_delivery_log
--    当前 PK：event_delivery_log_pkey (id)  目标：(tenant_id, id)
--    入站 FK → outbox_event：V170 已 DROP，分区表 PG 限制，不重建
-- ============================================================

ALTER TABLE batch.event_delivery_log DROP CONSTRAINT event_delivery_log_pkey;
ALTER TABLE batch.event_delivery_log ADD CONSTRAINT event_delivery_log_pkey
    PRIMARY KEY (tenant_id, id);

-- ============================================================
-- 3. event_outbox_retry
--    当前 PK：event_outbox_retry_pkey (id)  目标：(tenant_id, id)
--    入站 FK → outbox_event：V170 已 DROP，分区表 PG 限制，不重建
-- ============================================================

ALTER TABLE batch.event_outbox_retry DROP CONSTRAINT event_outbox_retry_pkey;
ALTER TABLE batch.event_outbox_retry ADD CONSTRAINT event_outbox_retry_pkey
    PRIMARY KEY (tenant_id, id);

-- ============================================================
-- 4. worker_report_outbox
--    当前 PK：worker_report_outbox_pkey (id)  目标：(tenant_id, id)
--    无入站/出站 FK
-- ============================================================

ALTER TABLE batch.worker_report_outbox DROP CONSTRAINT worker_report_outbox_pkey;
ALTER TABLE batch.worker_report_outbox ADD CONSTRAINT worker_report_outbox_pkey
    PRIMARY KEY (tenant_id, id);
