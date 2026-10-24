-- Step 是一个 Task 的镜像记录，同时包含可选的分区标识。
-- 可空外键列会让 MATCH SIMPLE 绕过无分区 Step 的校验。
-- 将 NULL 规范化为内部键 0，并在两侧都拒绝真实分区 ID 0。

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM batch.job_task WHERE job_partition_id = 0)
       OR EXISTS (SELECT 1 FROM batch.job_step_instance WHERE job_partition_id = 0) THEN
        RAISE EXCEPTION 'partition id 0 is reserved for the null-safe task/step reference key';
    END IF;
    IF EXISTS (
        SELECT 1
          FROM batch.job_step_instance step
          LEFT JOIN batch.job_task task
            ON task.id = step.job_task_id
           AND task.tenant_id = step.tenant_id
           AND task.job_instance_id = step.job_instance_id
           AND task.job_partition_id IS NOT DISTINCT FROM step.job_partition_id
         WHERE task.id IS NULL
    ) THEN
        RAISE EXCEPTION 'job_step_instance contains a task owned by another tenant, instance or partition';
    END IF;
END;
$$;

-- 全新数据库中的表为空；此迁移在种子数据或应用写入前执行。

ALTER TABLE batch.job_task
    ADD COLUMN job_partition_ref_key BIGINT
        -- squawk-ignore adding-field-with-default
        GENERATED ALWAYS AS (COALESCE(job_partition_id, 0::BIGINT)) STORED,
    ADD CONSTRAINT ck_job_task_partition_reference_nonzero
        CHECK (job_partition_id IS DISTINCT FROM 0) NOT VALID;
ALTER TABLE batch.job_task
    VALIDATE CONSTRAINT ck_job_task_partition_reference_nonzero;

-- 全新数据库中的表为空；此迁移在种子数据或应用写入前执行。

ALTER TABLE batch.job_step_instance
    ADD COLUMN job_partition_ref_key BIGINT
        -- squawk-ignore adding-field-with-default
        GENERATED ALWAYS AS (COALESCE(job_partition_id, 0::BIGINT)) STORED,
    ADD CONSTRAINT ck_job_step_partition_reference_nonzero
        CHECK (job_partition_id IS DISTINCT FROM 0) NOT VALID;
ALTER TABLE batch.job_step_instance
    VALIDATE CONSTRAINT ck_job_step_partition_reference_nonzero;

CREATE UNIQUE INDEX uk_job_task_step_ownership
    ON batch.job_task (tenant_id, id, job_instance_id, job_partition_ref_key);

-- 现有 UNIQUE (tenant_id, job_task_id) 索引可分别索引每个 Step 的父记录引用。
-- Partition → Task → Step 保留原有两条 CASCADE 路径，且不创建重复外键。

ALTER TABLE batch.job_step_instance
    DROP CONSTRAINT job_step_instance_job_task_id_fkey,
    DROP CONSTRAINT job_step_instance_job_partition_id_fkey;
ALTER TABLE batch.job_step_instance
    ADD CONSTRAINT fk_job_step_task_ownership
        FOREIGN KEY (tenant_id, job_task_id, job_instance_id, job_partition_ref_key)
        REFERENCES batch.job_task (tenant_id, id, job_instance_id, job_partition_ref_key)
        ON DELETE CASCADE NOT VALID;
ALTER TABLE batch.job_step_instance
    VALIDATE CONSTRAINT fk_job_step_task_ownership;

-- 现有显式列归档写入会重新计算生成的镜像列。
-- 全新数据库中的表为空；只有所有迁移完成后才会写入归档数据。

ALTER TABLE archive.job_task_archive
    ADD COLUMN job_partition_ref_key BIGINT
        -- squawk-ignore adding-field-with-default
        GENERATED ALWAYS AS (COALESCE(job_partition_id, 0::BIGINT)) STORED;
-- 全新数据库中的表为空；只有所有迁移完成后才会写入归档数据。

ALTER TABLE archive.job_step_instance_archive
    ADD COLUMN job_partition_ref_key BIGINT
        -- squawk-ignore adding-field-with-default
        GENERATED ALWAYS AS (COALESCE(job_partition_id, 0::BIGINT)) STORED;

COMMENT ON COLUMN batch.job_task.job_partition_ref_key IS
    '内部空值安全的分区引用键；0 表示 NULL，不是有效的任务分区 ID。';
COMMENT ON COLUMN batch.job_step_instance.job_partition_ref_key IS
    '由数据库生成的分区身份，用于原生 Step-to-Task 所有权外键。';
COMMENT ON COLUMN archive.job_task_archive.job_partition_ref_key IS
    '生成的 Task 分区引用身份的冷归档镜像。';
COMMENT ON COLUMN archive.job_step_instance_archive.job_partition_ref_key IS
    '生成的 Step 分区引用身份的冷归档镜像。';
COMMENT ON CONSTRAINT fk_job_step_task_ownership ON batch.job_step_instance IS
    'Step 的租户、JobInstance 和可选分区必须与 Task 匹配；删除 Task 时级联删除 Step。';
