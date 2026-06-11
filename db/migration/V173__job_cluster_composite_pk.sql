-- V173: job 簇 8 张表复合 PK 化（Citus distributed 前置）
--
-- 目的：将 job 簇 8 张表的 PRIMARY KEY 从单列 (id) 改为复合 (tenant_id, id)，
--       job_instance 特殊：V171 后 PK 已是 (id, biz_date)，本迁移改为 (tenant_id, id, biz_date)。
--       这是 Citus distributed table 的前置条件（分片键必须在 PK 中）。
-- 参见：docs/analysis/citus-poc-gates-2026-06-11.md
--
-- === FK 处置清单 ===
--
-- 引用 job_instance（分区表）的 FK —— 一律不重建（PG 不支持 FK → 分区表 unless 含分区键，
--   V171 已转应用层守护）：
--   · job_task_job_instance_id_fkey              ← V171 已 DROP，应用层守护
--   · job_partition_job_instance_id_fkey          ← V171 已 DROP，应用层守护
--   · job_step_instance_job_instance_id_fkey      ← V171 已 DROP，应用层守护
--   · job_execution_log_job_instance_id_fkey      ← V171 已 DROP，应用层守护
--   · compensation_command_related_job_instance_id_fkey ← V171 已 DROP，应用层守护
--
-- 引用 job_partition（普通表）的 FK —— DROP 后重建为复合 FK（保留原名 + ON DELETE 行为）：
--   · job_task_job_partition_id_fkey              ON DELETE CASCADE → 重建复合
--   · job_step_instance_job_partition_id_fkey     ON DELETE CASCADE → 重建复合
--   · job_execution_log_job_partition_id_fkey     ON DELETE SET NULL → 重建复合
--
-- 引用 job_task（普通表）的 FK —— DROP 后重建为复合 FK：
--   · job_step_instance_job_task_id_fkey          ON DELETE CASCADE → 重建复合
--
-- 其余 4 张表（job_step_instance / job_execution_log / compensation_command /
--   compensation_checkpoint / retry_schedule）无入站 FK，直接 DROP + ADD PK。
--
-- 量测结果：入站 FK 合计 8 条（V171 已 DROP 5 条/应用层守护，本波重建 4 条复合 FK）；
--           mapper 缺 tenant_id：retry_schedule 5 处（另见 Step 4 Java 改动）。

-- ============================================================
-- 1. job_instance（分区表，PK 含分区键）
--    V171 后 PK 名为 job_instance_p_pkey，列 = (id, biz_date)
--    目标：(tenant_id, id, biz_date)
-- ============================================================
ALTER TABLE batch.job_instance DROP CONSTRAINT job_instance_p_pkey;
ALTER TABLE batch.job_instance ADD CONSTRAINT job_instance_p_pkey
    PRIMARY KEY (tenant_id, id, biz_date);

-- ============================================================
-- 2. job_partition
--    当前 PK：job_partition_pkey (id)  目标：(tenant_id, id)
-- ============================================================

-- 先 DROP 入站 FK（V171 已 DROP job_partition_job_instance_id_fkey，此处不需再 DROP）
ALTER TABLE batch.job_task
    DROP CONSTRAINT IF EXISTS job_task_job_partition_id_fkey;
ALTER TABLE batch.job_step_instance
    DROP CONSTRAINT IF EXISTS job_step_instance_job_partition_id_fkey;
ALTER TABLE batch.job_execution_log
    DROP CONSTRAINT IF EXISTS job_execution_log_job_partition_id_fkey;

ALTER TABLE batch.job_partition DROP CONSTRAINT job_partition_pkey;
ALTER TABLE batch.job_partition ADD CONSTRAINT job_partition_pkey
    PRIMARY KEY (tenant_id, id);

-- 重建复合 FK（job_task → job_partition）
ALTER TABLE batch.job_task
    ADD CONSTRAINT job_task_job_partition_id_fkey
    FOREIGN KEY (tenant_id, job_partition_id) REFERENCES batch.job_partition (tenant_id, id)
    ON DELETE CASCADE;

-- 重建复合 FK（job_step_instance → job_partition）
ALTER TABLE batch.job_step_instance
    ADD CONSTRAINT job_step_instance_job_partition_id_fkey
    FOREIGN KEY (tenant_id, job_partition_id) REFERENCES batch.job_partition (tenant_id, id)
    ON DELETE CASCADE;

-- 重建复合 FK（job_execution_log → job_partition）
ALTER TABLE batch.job_execution_log
    ADD CONSTRAINT job_execution_log_job_partition_id_fkey
    FOREIGN KEY (tenant_id, job_partition_id) REFERENCES batch.job_partition (tenant_id, id)
    ON DELETE SET NULL;

-- ============================================================
-- 3. job_task
--    当前 PK：job_task_pkey (id)  目标：(tenant_id, id)
-- ============================================================

-- 先 DROP 入站 FK
ALTER TABLE batch.job_step_instance
    DROP CONSTRAINT IF EXISTS job_step_instance_job_task_id_fkey;

ALTER TABLE batch.job_task DROP CONSTRAINT job_task_pkey;
ALTER TABLE batch.job_task ADD CONSTRAINT job_task_pkey
    PRIMARY KEY (tenant_id, id);

-- 重建复合 FK（job_step_instance → job_task）
ALTER TABLE batch.job_step_instance
    ADD CONSTRAINT job_step_instance_job_task_id_fkey
    FOREIGN KEY (tenant_id, job_task_id) REFERENCES batch.job_task (tenant_id, id)
    ON DELETE CASCADE;

-- ============================================================
-- 4. job_step_instance
--    当前 PK：job_step_instance_pkey (id)  目标：(tenant_id, id)
--    job_step_instance_job_instance_id_fkey 已由 V171 DROP，不重建
-- ============================================================
ALTER TABLE batch.job_step_instance DROP CONSTRAINT job_step_instance_pkey;
ALTER TABLE batch.job_step_instance ADD CONSTRAINT job_step_instance_pkey
    PRIMARY KEY (tenant_id, id);

-- ============================================================
-- 5. job_execution_log
--    当前 PK：job_execution_log_pkey (id)  目标：(tenant_id, id)
--    无入站 FK
-- ============================================================
ALTER TABLE batch.job_execution_log DROP CONSTRAINT job_execution_log_pkey;
ALTER TABLE batch.job_execution_log ADD CONSTRAINT job_execution_log_pkey
    PRIMARY KEY (tenant_id, id);

-- ============================================================
-- 6. compensation_command
--    当前 PK：compensation_command_pkey (id)  目标：(tenant_id, id)
--    compensation_command_related_job_instance_id_fkey 已由 V171 DROP，不重建
-- ============================================================
ALTER TABLE batch.compensation_command DROP CONSTRAINT compensation_command_pkey;
ALTER TABLE batch.compensation_command ADD CONSTRAINT compensation_command_pkey
    PRIMARY KEY (tenant_id, id);

-- ============================================================
-- 7. compensation_checkpoint
--    当前 PK：compensation_checkpoint_pkey (id)  目标：(tenant_id, id)
--    无入站 FK，无 mapper
-- ============================================================
ALTER TABLE batch.compensation_checkpoint DROP CONSTRAINT compensation_checkpoint_pkey;
ALTER TABLE batch.compensation_checkpoint ADD CONSTRAINT compensation_checkpoint_pkey
    PRIMARY KEY (tenant_id, id);

-- ============================================================
-- 8. retry_schedule
--    当前 PK：retry_schedule_pkey (id)  目标：(tenant_id, id)
--    无入站 FK
-- ============================================================
ALTER TABLE batch.retry_schedule DROP CONSTRAINT retry_schedule_pkey;
ALTER TABLE batch.retry_schedule ADD CONSTRAINT retry_schedule_pkey
    PRIMARY KEY (tenant_id, id);
