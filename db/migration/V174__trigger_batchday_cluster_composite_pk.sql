-- V174: trigger/batch_day 簇 10 张表复合 PK 化（Citus distributed 前置）
--
-- 目的：将 trigger/batch_day 簇 10 张表的 PRIMARY KEY 从单列 (id) 改为复合 (tenant_id, id)，
--       这是 Citus distributed table 的前置条件（分片键必须在 PK 中）。
-- 参见：docs/analysis/citus-poc-gates-2026-06-11.md
--
-- === FK 处置清单 ===
--
-- 入站 FK 合计 3 条，均可重建为复合 FK（两端表均有 tenant_id）：
--
--   · batch_day_replay_entry_session_id_fkey
--       batch.batch_day_replay_entry(session_id) → batch.batch_day_replay_session(id)
--       → 重建为复合 FK: (tenant_id, session_id) → (tenant_id, id)
--
--   · job_instance_trigger_request_id_fkey
--       batch.job_instance(trigger_request_id) → batch.trigger_request(id)
--       job_instance.relkind='r'（普通表，非分区父表），有 tenant_id
--       → 重建为复合 FK: (tenant_id, trigger_request_id) → (tenant_id, id)
--
--   · trigger_misfire_pending_trigger_runtime_state_id_fkey  ON DELETE CASCADE
--       batch.trigger_misfire_pending(trigger_runtime_state_id) → batch.trigger_runtime_state(id)
--       → 重建为复合 FK: (tenant_id, trigger_runtime_state_id) → (tenant_id, id)  ON DELETE CASCADE
--
-- 量测结果：入站 FK 合计 3 条，全部重建为复合 FK；
--           mapper 缺 tenant_id 处（见 Step 4 Java 改动）：
--             batch-trigger: TriggerRequestMapper.selectById 1 处；
--                            TriggerMisfirePendingMapper.selectById/approve/reject/linkCatchUpRequest 4 处；
--                            TriggerRuntimeStateMapper.claimForSchedule/advanceAfterFire/rescheduleNextFireTime 3 处；
--                            TriggerOutboxEventMapper.markPublishing/markPublished/markFailed 3 处；
--             batch-orchestrator: BatchDayInstanceMapper.updateWithCas 1 处；
--                                 BatchDayReplayEntryMapper.updateStatus 1 处。

-- ============================================================
-- 1. trigger_request
--    当前 PK：trigger_request_pkey (id)  目标：(tenant_id, id)
--    入站 FK：job_instance_trigger_request_id_fkey
-- ============================================================

-- 生产 DB 名 = job_instance_trigger_request_id_fkey
-- 新建 DB（V171 月分区迁移脚本创建）名 = job_instance_p_trigger_request_id_fkey（含 _p_ 前缀）
-- 两条 IF EXISTS 均 DROP，保证新旧 DB 均可过 migration
ALTER TABLE batch.job_instance
    DROP CONSTRAINT IF EXISTS job_instance_trigger_request_id_fkey;
ALTER TABLE batch.job_instance
    DROP CONSTRAINT IF EXISTS job_instance_p_trigger_request_id_fkey;

ALTER TABLE batch.trigger_request DROP CONSTRAINT trigger_request_pkey;
ALTER TABLE batch.trigger_request ADD CONSTRAINT trigger_request_pkey
    PRIMARY KEY (tenant_id, id);

-- 重建复合 FK（job_instance → trigger_request）
ALTER TABLE batch.job_instance
    ADD CONSTRAINT job_instance_trigger_request_id_fkey
    FOREIGN KEY (tenant_id, trigger_request_id) REFERENCES batch.trigger_request (tenant_id, id);

-- ============================================================
-- 2. trigger_outbox_event
--    当前 PK：trigger_outbox_event_pkey (id)  目标：(tenant_id, id)
--    无入站 FK
-- ============================================================

ALTER TABLE batch.trigger_outbox_event DROP CONSTRAINT trigger_outbox_event_pkey;
ALTER TABLE batch.trigger_outbox_event ADD CONSTRAINT trigger_outbox_event_pkey
    PRIMARY KEY (tenant_id, id);

-- ============================================================
-- 3. trigger_runtime_state
--    当前 PK：trigger_runtime_state_pkey (id)  目标：(tenant_id, id)
--    入站 FK：trigger_misfire_pending_trigger_runtime_state_id_fkey
-- ============================================================

ALTER TABLE batch.trigger_misfire_pending
    DROP CONSTRAINT IF EXISTS trigger_misfire_pending_trigger_runtime_state_id_fkey;

ALTER TABLE batch.trigger_runtime_state DROP CONSTRAINT trigger_runtime_state_pkey;
ALTER TABLE batch.trigger_runtime_state ADD CONSTRAINT trigger_runtime_state_pkey
    PRIMARY KEY (tenant_id, id);

-- 重建复合 FK（trigger_misfire_pending → trigger_runtime_state）ON DELETE CASCADE
ALTER TABLE batch.trigger_misfire_pending
    ADD CONSTRAINT trigger_misfire_pending_trigger_runtime_state_id_fkey
    FOREIGN KEY (tenant_id, trigger_runtime_state_id) REFERENCES batch.trigger_runtime_state (tenant_id, id)
    ON DELETE CASCADE;

-- ============================================================
-- 4. trigger_misfire_pending
--    当前 PK：trigger_misfire_pending_pkey (id)  目标：(tenant_id, id)
--    无入站 FK
-- ============================================================

ALTER TABLE batch.trigger_misfire_pending DROP CONSTRAINT trigger_misfire_pending_pkey;
ALTER TABLE batch.trigger_misfire_pending ADD CONSTRAINT trigger_misfire_pending_pkey
    PRIMARY KEY (tenant_id, id);

-- ============================================================
-- 5. batch_day_instance
--    当前 PK：batch_day_instance_pkey (id)  目标：(tenant_id, id)
--    无入站 FK
-- ============================================================

ALTER TABLE batch.batch_day_instance DROP CONSTRAINT batch_day_instance_pkey;
ALTER TABLE batch.batch_day_instance ADD CONSTRAINT batch_day_instance_pkey
    PRIMARY KEY (tenant_id, id);

-- ============================================================
-- 6. batch_day_waiting_launch
--    当前 PK：batch_day_waiting_launch_pkey (id)  目标：(tenant_id, id)
--    无入站 FK
-- ============================================================

ALTER TABLE batch.batch_day_waiting_launch DROP CONSTRAINT batch_day_waiting_launch_pkey;
ALTER TABLE batch.batch_day_waiting_launch ADD CONSTRAINT batch_day_waiting_launch_pkey
    PRIMARY KEY (tenant_id, id);

-- ============================================================
-- 7. batch_day_replay_session
--    当前 PK：batch_day_replay_session_pkey (id)  目标：(tenant_id, id)
--    入站 FK：batch_day_replay_entry_session_id_fkey
-- ============================================================

ALTER TABLE batch.batch_day_replay_entry
    DROP CONSTRAINT IF EXISTS batch_day_replay_entry_session_id_fkey;

ALTER TABLE batch.batch_day_replay_session DROP CONSTRAINT batch_day_replay_session_pkey;
ALTER TABLE batch.batch_day_replay_session ADD CONSTRAINT batch_day_replay_session_pkey
    PRIMARY KEY (tenant_id, id);

-- 重建复合 FK（batch_day_replay_entry → batch_day_replay_session）
ALTER TABLE batch.batch_day_replay_entry
    ADD CONSTRAINT batch_day_replay_entry_session_id_fkey
    FOREIGN KEY (tenant_id, session_id) REFERENCES batch.batch_day_replay_session (tenant_id, id);

-- ============================================================
-- 8. batch_day_replay_entry
--    当前 PK：batch_day_replay_entry_pkey (id)  目标：(tenant_id, id)
--    无入站 FK（FK 由上方步骤 7 已重建）
-- ============================================================

ALTER TABLE batch.batch_day_replay_entry DROP CONSTRAINT batch_day_replay_entry_pkey;
ALTER TABLE batch.batch_day_replay_entry ADD CONSTRAINT batch_day_replay_entry_pkey
    PRIMARY KEY (tenant_id, id);

-- ============================================================
-- 9. batch_day_operation_audit
--    当前 PK：batch_day_operation_audit_pkey (id)  目标：(tenant_id, id)
--    无入站 FK
-- ============================================================

ALTER TABLE batch.batch_day_operation_audit DROP CONSTRAINT batch_day_operation_audit_pkey;
ALTER TABLE batch.batch_day_operation_audit ADD CONSTRAINT batch_day_operation_audit_pkey
    PRIMARY KEY (tenant_id, id);

-- ============================================================
-- 10. tenant_scheduler_snapshot
--     当前 PK：tenant_scheduler_snapshot_pkey (id)  目标：(tenant_id, id)
--     无入站 FK
-- ============================================================

ALTER TABLE batch.tenant_scheduler_snapshot DROP CONSTRAINT tenant_scheduler_snapshot_pkey;
ALTER TABLE batch.tenant_scheduler_snapshot ADD CONSTRAINT tenant_scheduler_snapshot_pkey
    PRIMARY KEY (tenant_id, id);
