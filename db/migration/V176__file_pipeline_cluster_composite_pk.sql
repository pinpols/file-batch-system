-- V176: file/pipeline 簇 8 张表复合 PK 化（Citus distributed 前置）
--
-- 目的：将 file/pipeline 簇 8 张表的 PRIMARY KEY 从单列 (id) 改为复合 (tenant_id, id)，
--       这是 Citus distributed table 的前置条件（分片键必须在 PK 中）。
-- 参见：docs/analysis/citus-poc-gates-2026-06-11.md
--
-- === 量测结果 ===
--
--   · file_record
--       当前 PK：file_record_pkey (id)  目标：(tenant_id, id)
--       入站 FK：
--         file_audit_log_file_id_fkey (file_id → id) ON DELETE SET NULL
--         file_dispatch_record_file_id_fkey (file_id → id) ON DELETE CASCADE
--         pipeline_instance_file_id_fkey (file_id → id) ON DELETE NO ACTION
--
--   · file_dispatch_record
--       当前 PK：file_dispatch_record_pkey (id)  目标：(tenant_id, id)
--       出站 FK → file_record(id)：ON DELETE CASCADE → 重建复合
--       出站 FK → pipeline_instance(id)：ON DELETE NO ACTION → 重建复合
--
--   · file_error_record
--       当前 PK：file_error_record_pkey (id)  目标：(tenant_id, id)
--       无 FK 约束
--
--   · file_audit_log
--       当前 PK：file_audit_log_pkey (id)  目标：(tenant_id, id)
--       出站 FK → file_record(id)：ON DELETE SET NULL → 重建复合
--
--   · file_channel_health
--       当前 PK：file_channel_health_pkey (id)  目标：(tenant_id, id)
--       无 FK 约束
--
--   · pipeline_instance
--       当前 PK：pipeline_instance_pkey (id)  目标：(tenant_id, id)
--       入站 FK：
--         file_dispatch_record_pipeline_instance_id_fkey (pipeline_instance_id → id) ON DELETE NO ACTION
--         pipeline_step_run_pipeline_instance_id_fkey (pipeline_instance_id → id) ON DELETE NO ACTION
--       出站 FK → file_record(id)：本迁移后 file_record PK 变复合 → 重建复合
--       出站 FK → pipeline_definition(id)：pipeline_definition PK 仍单列，不重建复合
--       出站 FK → job_instance(id)：job_instance PK 仍单列，不重建复合
--
--   · pipeline_progress
--       当前 PK：pipeline_progress_pkey (id)  目标：(tenant_id, id)
--       无 FK 约束（pipeline_instance_id 仅有索引，无外键）
--
--   · pipeline_step_run（②类特殊：当前无 tenant_id 列）
--       步骤：ADD COLUMN → 回填 → SET NOT NULL → 复合 PK
--       当前 PK：pipeline_step_run_pkey (id)  目标：(tenant_id, id)
--       出站 FK → pipeline_instance(id)：本迁移后 pipeline_instance PK 变复合 → 重建复合
--
-- === FK 处置清单 ===
--
-- 本波 DROP 并重建复合 FK（双方都完成复合 PK 后才重建）：
--   · file_audit_log_file_id_fkey              (SET NULL)  → 重建复合
--   · file_dispatch_record_file_id_fkey        (CASCADE)   → 重建复合
--   · file_dispatch_record_pipeline_instance_id_fkey (NO ACTION) → 重建复合
--   · pipeline_instance_file_id_fkey           (NO ACTION) → 重建复合
--   · pipeline_step_run_pipeline_instance_id_fkey (NO ACTION) → 重建复合
--
-- 保持不动（FK 所指表 PK 仍单列）：
--   · pipeline_instance_pipeline_definition_id_fkey → pipeline_definition PK=(id)
--   · pipeline_instance_related_job_instance_id_fkey → job_instance PK=(id)
--
-- === mapper 缺口决策 ===
--
-- PlatformFileRuntimeMapper.insertStepRun：INSERT 列不含 tenant_id，从 pipeline_instance 上下文取值；
-- Citus 启用时须同步补写（已改造，见 Step 4 Java 改动）。
--
-- 量测：入站 FK 合计 5 条全部重建复合；pipeline_step_run 回填行数由 pipeline_instance 关联得。
--
-- 禁 psql 元命令；禁 BEGIN/COMMIT（Flyway 管事务）

-- ============================================================
-- 0. pipeline_step_run 补 tenant_id（特殊表：原无此列）
-- ============================================================

ALTER TABLE batch.pipeline_step_run
    ADD COLUMN tenant_id VARCHAR(64);

UPDATE batch.pipeline_step_run sr
    SET tenant_id = pi.tenant_id
    FROM batch.pipeline_instance pi
    WHERE sr.pipeline_instance_id = pi.id;

ALTER TABLE batch.pipeline_step_run
    ALTER COLUMN tenant_id SET NOT NULL;

-- ============================================================
-- 1. file_record
--    当前 PK：file_record_pkey (id)  目标：(tenant_id, id)
--    先 DROP 所有入站 FK，改 PK 后重建
-- ============================================================

ALTER TABLE batch.file_audit_log
    DROP CONSTRAINT IF EXISTS file_audit_log_file_id_fkey;
ALTER TABLE batch.file_dispatch_record
    DROP CONSTRAINT IF EXISTS file_dispatch_record_file_id_fkey;
ALTER TABLE batch.pipeline_instance
    DROP CONSTRAINT IF EXISTS pipeline_instance_file_id_fkey;

ALTER TABLE batch.file_record DROP CONSTRAINT file_record_pkey;
ALTER TABLE batch.file_record ADD CONSTRAINT file_record_pkey
    PRIMARY KEY (tenant_id, id);

-- 重建复合 FK（file_audit_log → file_record）
ALTER TABLE batch.file_audit_log
    ADD CONSTRAINT file_audit_log_file_id_fkey
    FOREIGN KEY (tenant_id, file_id) REFERENCES batch.file_record (tenant_id, id)
    ON DELETE SET NULL;

-- 重建复合 FK（file_dispatch_record → file_record）
ALTER TABLE batch.file_dispatch_record
    ADD CONSTRAINT file_dispatch_record_file_id_fkey
    FOREIGN KEY (tenant_id, file_id) REFERENCES batch.file_record (tenant_id, id)
    ON DELETE CASCADE;

-- ============================================================
-- 2. pipeline_instance
--    当前 PK：pipeline_instance_pkey (id)  目标：(tenant_id, id)
--    先 DROP 所有入站 FK，改 PK 后重建
--    注：pipeline_instance_pipeline_definition_id_fkey、pipeline_instance_related_job_instance_id_fkey
--        所指表 PK 仍单列，不重建复合（原 FK 已在 DROP pipeline_instance PK 前后无需变动，保持不动）
-- ============================================================

ALTER TABLE batch.file_dispatch_record
    DROP CONSTRAINT IF EXISTS file_dispatch_record_pipeline_instance_id_fkey;
ALTER TABLE batch.pipeline_step_run
    DROP CONSTRAINT IF EXISTS pipeline_step_run_pipeline_instance_id_fkey;

ALTER TABLE batch.pipeline_instance DROP CONSTRAINT pipeline_instance_pkey;
ALTER TABLE batch.pipeline_instance ADD CONSTRAINT pipeline_instance_pkey
    PRIMARY KEY (tenant_id, id);

-- 重建复合 FK（pipeline_instance → file_record）
ALTER TABLE batch.pipeline_instance
    ADD CONSTRAINT pipeline_instance_file_id_fkey
    FOREIGN KEY (tenant_id, file_id) REFERENCES batch.file_record (tenant_id, id);

-- 重建复合 FK（file_dispatch_record → pipeline_instance）
ALTER TABLE batch.file_dispatch_record
    ADD CONSTRAINT file_dispatch_record_pipeline_instance_id_fkey
    FOREIGN KEY (tenant_id, pipeline_instance_id) REFERENCES batch.pipeline_instance (tenant_id, id);

-- ============================================================
-- 3. file_dispatch_record
--    当前 PK：file_dispatch_record_pkey (id)  目标：(tenant_id, id)
--    入站 FK 已在步骤 1/2 处理完毕
-- ============================================================

ALTER TABLE batch.file_dispatch_record DROP CONSTRAINT file_dispatch_record_pkey;
ALTER TABLE batch.file_dispatch_record ADD CONSTRAINT file_dispatch_record_pkey
    PRIMARY KEY (tenant_id, id);

-- ============================================================
-- 4. file_audit_log
--    当前 PK：file_audit_log_pkey (id)  目标：(tenant_id, id)
--    出站 FK → file_record 已在步骤 1 重建复合
-- ============================================================

ALTER TABLE batch.file_audit_log DROP CONSTRAINT file_audit_log_pkey;
ALTER TABLE batch.file_audit_log ADD CONSTRAINT file_audit_log_pkey
    PRIMARY KEY (tenant_id, id);

-- ============================================================
-- 5. file_error_record
--    当前 PK：file_error_record_pkey (id)  目标：(tenant_id, id)
--    无 FK 约束
-- ============================================================

ALTER TABLE batch.file_error_record DROP CONSTRAINT file_error_record_pkey;
ALTER TABLE batch.file_error_record ADD CONSTRAINT file_error_record_pkey
    PRIMARY KEY (tenant_id, id);

-- ============================================================
-- 6. file_channel_health
--    当前 PK：file_channel_health_pkey (id)  目标：(tenant_id, id)
--    无 FK 约束
-- ============================================================

ALTER TABLE batch.file_channel_health DROP CONSTRAINT file_channel_health_pkey;
ALTER TABLE batch.file_channel_health ADD CONSTRAINT file_channel_health_pkey
    PRIMARY KEY (tenant_id, id);

-- ============================================================
-- 7. pipeline_progress
--    当前 PK：pipeline_progress_pkey (id)  目标：(tenant_id, id)
--    无 FK 约束（pipeline_instance_id 仅有索引，无外键）
-- ============================================================

ALTER TABLE batch.pipeline_progress DROP CONSTRAINT pipeline_progress_pkey;
ALTER TABLE batch.pipeline_progress ADD CONSTRAINT pipeline_progress_pkey
    PRIMARY KEY (tenant_id, id);

-- ============================================================
-- 8. pipeline_step_run
--    当前 PK：pipeline_step_run_pkey (id)  目标：(tenant_id, id)
--    步骤 0 已补 tenant_id 列；出站 FK 在步骤 2 已 DROP
-- ============================================================

ALTER TABLE batch.pipeline_step_run DROP CONSTRAINT pipeline_step_run_pkey;
ALTER TABLE batch.pipeline_step_run ADD CONSTRAINT pipeline_step_run_pkey
    PRIMARY KEY (tenant_id, id);

-- 重建复合 FK（pipeline_step_run → pipeline_instance）
ALTER TABLE batch.pipeline_step_run
    ADD CONSTRAINT pipeline_step_run_pipeline_instance_id_fkey
    FOREIGN KEY (tenant_id, pipeline_instance_id) REFERENCES batch.pipeline_instance (tenant_id, id);

-- ============================================================
-- 9. archive.pipeline_step_run_archive 补 tenant_id
--    CLAUDE.md: 热表 batch.* 与 archive.*_archive 必须 1:1 字段镜像；
--    pipeline_step_run 本迁移补了 tenant_id，归档表需同 PR 对齐
-- ============================================================

ALTER TABLE archive.pipeline_step_run_archive
    ADD COLUMN tenant_id VARCHAR(64);
