-- =============================================================================
-- R3 第三轮深扫整改：约束完整性大补丁
-- =============================================================================
-- 覆盖 R3-P0-1/2/4 + R3-P1-4/5/6/7/8 + R3-P2-7：
-- 1) 4 张表的 UNIQUE+NULL bypass（PG NULL ≠ NULL 导致重复行）→ partial unique index
-- 2) workflow_run / replay_session / result_version 等业务唯一性 + CHECK 枚举
-- 3) data_quality_check 加 FK + updated_at
-- 4) calendar_holiday 加 scope=GROUP 强制 CHECK
--
-- 风险评估：
-- - 所有 partial unique index 与原 UNIQUE 约束语义重叠，仅在 NULL 行覆盖盲区；
--   存量数据若已存在重复（idempotency_key=NULL 多行），新索引会建失败 → 先 DROP
--   原约束、再 INSERT NOT EXISTS 风格创建。
-- - data_quality_check 加 FK 时若 SPI sink 已写了 rule_id 找不到的行，会失败；
--   先用 ON DELETE SET NULL 减少阻塞面。
-- - 部分 CHECK 加约束时若 DB 存量有非法值，迁移会失败；选择"先 NOT VALID 加约束
--   + 后续 VALIDATE"模式让运维人工 backfill。
-- =============================================================================

-- ─── 1. job_partition.idempotency_key UNIQUE NULL bypass (R3-P0-1) ──────────
-- 原 UNIQUE 在 idempotency_key=NULL 的行上失效，CLAIM 防重失效。改 partial unique。
ALTER TABLE batch.job_partition DROP CONSTRAINT IF EXISTS uk_job_partition_idempotency_key;

CREATE UNIQUE INDEX IF NOT EXISTS uk_job_partition_idempotency_key
    ON batch.job_partition (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- ─── 2. file_record (tenant, checksum, path) UNIQUE NULL bypass (R3-P0-2) ───
-- checksum_value NULL（checksum_type=NONE）的行可无限重复。
ALTER TABLE batch.file_record DROP CONSTRAINT IF EXISTS uk_file_record_tenant_checksum_path;

-- 有 checksum 的行保留原唯一性
CREATE UNIQUE INDEX IF NOT EXISTS uk_file_record_with_checksum
    ON batch.file_record (tenant_id, checksum_value, storage_path)
    WHERE checksum_value IS NOT NULL;

-- 无 checksum 时退化为 (tenant, path) 唯一，至少防同路径重复
CREATE UNIQUE INDEX IF NOT EXISTS uk_file_record_no_checksum
    ON batch.file_record (tenant_id, storage_path)
    WHERE checksum_value IS NULL;

-- ─── 3. job_task.uk_job_task_partition_seq NULL bypass (R3-P1-7) ────────────
-- job_partition_id=NULL 的 GENERAL job 可重复同 task_seq。
ALTER TABLE batch.job_task DROP CONSTRAINT IF EXISTS uk_job_task_partition_seq;

-- 有 partition_id 的行保留 (partition_id, seq) 唯一
CREATE UNIQUE INDEX IF NOT EXISTS uk_job_task_partition_seq
    ON batch.job_task (job_partition_id, task_seq)
    WHERE job_partition_id IS NOT NULL;

-- 无 partition_id 的行用 (job_instance_id, seq) 兜底
CREATE UNIQUE INDEX IF NOT EXISTS uk_job_task_no_partition_seq
    ON batch.job_task (job_instance_id, task_seq)
    WHERE job_partition_id IS NULL;

-- ─── 4. pipeline_instance 无业务 UNIQUE (R3-P1-8) ──────────────────────────
-- 同 (tenant, job_code, file_id) 可重复 CREATED；至少强制 related_job_instance_id 唯一。
CREATE UNIQUE INDEX IF NOT EXISTS uk_pipeline_instance_job_instance
    ON batch.pipeline_instance (related_job_instance_id)
    WHERE related_job_instance_id IS NOT NULL;

-- ─── 5. workflow_run 业务 UNIQUE + 索引 (R3-P1-4) ──────────────────────────
-- 同 (tenant, workflow_def, biz_date) 不应同时有多个非终态 run。
CREATE UNIQUE INDEX IF NOT EXISTS uk_workflow_run_active
    ON batch.workflow_run (tenant_id, workflow_definition_id, biz_date)
    WHERE run_status IN ('CREATED', 'RUNNING');

-- 高频 lookup 索引（按 def + biz_date 查具体 run）
CREATE INDEX IF NOT EXISTS idx_workflow_run_def_bizdate
    ON batch.workflow_run (tenant_id, workflow_definition_id, biz_date);

-- ─── 6. batch_day_replay_session 枚举 CHECK (R3-P1-5) ──────────────────────
-- result_policy / config_version_policy 直写 DB 非法值会进未定义状态机分支。
-- 用 NOT VALID 让存量数据不阻塞；运维 backfill 后 VALIDATE CONSTRAINT。
ALTER TABLE batch.batch_day_replay_session
    ADD CONSTRAINT ck_replay_session_result_policy
    CHECK (result_policy IS NULL
           OR result_policy IN ('CREATE_NEW_VERSION', 'KEEP_BOTH', 'MANUAL_CONFIRM_EFFECTIVE'))
    NOT VALID;

ALTER TABLE batch.batch_day_replay_session
    ADD CONSTRAINT ck_replay_session_config_version_policy
    CHECK (config_version_policy IS NULL
           OR config_version_policy IN ('LATEST', 'PINNED', 'AS_SOURCE'))
    NOT VALID;

-- ─── 7. result_version.dq_gate_status CHECK (R3-P1-6) ──────────────────────
ALTER TABLE batch.result_version
    ADD CONSTRAINT ck_result_version_dq_gate_status
    CHECK (dq_gate_status IS NULL OR dq_gate_status IN ('PASS', 'WARN', 'BLOCKED'))
    NOT VALID;

ALTER TABLE archive.result_version_archive
    ADD CONSTRAINT ck_result_version_archive_dq_gate_status
    CHECK (dq_gate_status IS NULL OR dq_gate_status IN ('PASS', 'WARN', 'BLOCKED'))
    NOT VALID;

-- ─── 8. data_quality_check FK + updated_at (R3-P0-4) ────────────────────────
-- rule_id 不再悬空；rule 删除时 SET NULL（保留审计行但解除引用）。
-- 加 updated_at 让 archive retention scheduler 有 timestamp 决策依据。
ALTER TABLE batch.data_quality_check
    ADD CONSTRAINT fk_dq_check_rule_id
    FOREIGN KEY (rule_id) REFERENCES batch.data_quality_rule(id) ON DELETE SET NULL
    NOT VALID;

ALTER TABLE batch.data_quality_check
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE archive.data_quality_check_archive
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

-- ─── 9. calendar_holiday.scope=GROUP 强制 group_code (R3-P2-7) ──────────────
ALTER TABLE batch.calendar_holiday
    ADD CONSTRAINT ck_calendar_holiday_group_code_required
    CHECK (scope <> 'GROUP' OR group_code IS NOT NULL)
    NOT VALID;

-- =============================================================================
-- 上线指南（DBA 操作）：
-- 1) 灰度环境跑该迁移；若 partial unique 因存量重复行失败，先用 ad-hoc SQL 清理
--    重复后重跑：
--      SELECT tenant_id, idempotency_key, COUNT(*)
--      FROM batch.job_partition WHERE idempotency_key IS NOT NULL
--      GROUP BY 1,2 HAVING COUNT(*) > 1;
-- 2) NOT VALID 约束运维窗口期内逐表 `VALIDATE CONSTRAINT`：
--      ALTER TABLE batch.batch_day_replay_session
--      VALIDATE CONSTRAINT ck_replay_session_result_policy;
-- 3) data_quality_check FK 同步 VALIDATE。
-- =============================================================================
