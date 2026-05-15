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

-- 历史脏数据 dedup:某些 dev / 联调环境老 import 测试在 checksum 启用前留下了
-- (tenant_id, storage_path) 重复 file_record 行(全部 checksum_value IS NULL),
-- 会让下方 UNIQUE INDEX 创建失败。每组按 id 保留最新(最大)一行,删 id 更小的旧副本。
-- 干净库下两段都是 no-op(EXISTS / JOIN 0 命中)。
--
-- (1) 把指向"将删副本"的 file_audit_log 重指到每组保留的 winner id,避免 FK 阻挡
UPDATE batch.file_audit_log fal
SET file_id = winner.winner_id
FROM (
  SELECT a.id AS loser_id, MAX(b.id) AS winner_id
  FROM batch.file_record a
  JOIN batch.file_record b ON b.tenant_id = a.tenant_id
    AND b.storage_path = a.storage_path
    AND b.checksum_value IS NULL
  WHERE a.checksum_value IS NULL
    AND EXISTS (
      SELECT 1 FROM batch.file_record c
      WHERE c.tenant_id = a.tenant_id
        AND c.storage_path = a.storage_path
        AND c.checksum_value IS NULL
        AND c.id > a.id
    )
  GROUP BY a.id
) winner
WHERE fal.file_id = winner.loser_id;

-- (2) 删 file_record 重复行(保留每组 id 最大者)
DELETE FROM batch.file_record a
USING batch.file_record b
WHERE a.checksum_value IS NULL
  AND b.checksum_value IS NULL
  AND a.tenant_id = b.tenant_id
  AND a.storage_path = b.storage_path
  AND a.id < b.id;

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
-- PG 不支持 ADD CONSTRAINT IF NOT EXISTS for CHECK/FK；用 DO 块保证 idempotent（Flyway 重跑或 docker test 环境重复迁移不挂）。
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_replay_session_result_policy') THEN
        ALTER TABLE batch.batch_day_replay_session
            ADD CONSTRAINT ck_replay_session_result_policy
            CHECK (result_policy IS NULL
                   OR result_policy IN ('CREATE_NEW_VERSION', 'KEEP_BOTH', 'MANUAL_CONFIRM_EFFECTIVE'))
            NOT VALID;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_replay_session_config_version_policy') THEN
        -- 真实业务枚举（见 BatchDayReplayService / DefaultLaunchService）：
        --   USE_ORIGINAL_CONFIG（默认，复用原始 config 快照）
        --   SNAPSHOT_JOB_DEFINITION_VERSION_ON_CREATE（创建时快照当前 job_definition 版本）
        -- 之前的 'LATEST'/'PINNED'/'AS_SOURCE' 是文档臆造值，与代码不匹配。
        ALTER TABLE batch.batch_day_replay_session
            ADD CONSTRAINT ck_replay_session_config_version_policy
            CHECK (config_version_policy IS NULL
                   OR config_version_policy IN (
                       'USE_ORIGINAL_CONFIG',
                       'SNAPSHOT_JOB_DEFINITION_VERSION_ON_CREATE'))
            NOT VALID;
    END IF;
END $$;

-- ─── 7. result_version.dq_gate_status CHECK (R3-P1-6) ──────────────────────
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_result_version_dq_gate_status') THEN
        ALTER TABLE batch.result_version
            ADD CONSTRAINT ck_result_version_dq_gate_status
            CHECK (dq_gate_status IS NULL OR dq_gate_status IN ('PASS', 'WARN', 'BLOCKED'))
            NOT VALID;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_result_version_archive_dq_gate_status') THEN
        ALTER TABLE archive.result_version_archive
            ADD CONSTRAINT ck_result_version_archive_dq_gate_status
            CHECK (dq_gate_status IS NULL OR dq_gate_status IN ('PASS', 'WARN', 'BLOCKED'))
            NOT VALID;
    END IF;
END $$;

-- ─── 8. data_quality_check FK + updated_at (R3-P0-4) ────────────────────────
-- rule_id 不再悬空；rule 删除时 SET NULL（保留审计行但解除引用）。
-- 加 updated_at 让 archive retention scheduler 有 timestamp 决策依据。
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_dq_check_rule_id') THEN
        ALTER TABLE batch.data_quality_check
            ADD CONSTRAINT fk_dq_check_rule_id
            FOREIGN KEY (rule_id) REFERENCES batch.data_quality_rule(id) ON DELETE SET NULL
            NOT VALID;
    END IF;
END $$;

ALTER TABLE batch.data_quality_check
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE archive.data_quality_check_archive
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

-- ─── 9. calendar_holiday.scope=GROUP 强制 group_code (R3-P2-7) ──────────────
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_calendar_holiday_group_code_required') THEN
        ALTER TABLE batch.calendar_holiday
            ADD CONSTRAINT ck_calendar_holiday_group_code_required
            CHECK (scope <> 'GROUP' OR group_code IS NOT NULL)
            NOT VALID;
    END IF;
END $$;

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
