-- 恢复 V173 移除的 Task → 分区 JobInstance 引用。
-- 全新数据库部署会在任何运行数据或种子数据写入前应用此迁移。

ALTER TABLE batch.job_task
    -- 全新数据库中的表为空；不支持旧数据或在线回填。
    -- squawk-ignore adding-required-field
    ADD COLUMN job_instance_biz_date DATE NOT NULL;

CREATE INDEX idx_job_task_tenant_instance_date
    ON batch.job_task (tenant_id, job_instance_id, job_instance_biz_date);

ALTER TABLE batch.job_task
    ADD CONSTRAINT fk_job_task_instance_tenant_date
        FOREIGN KEY (tenant_id, job_instance_id, job_instance_biz_date)
        REFERENCES batch.job_instance (tenant_id, id, biz_date)
        ON DELETE NO ACTION
        NOT VALID;

ALTER TABLE batch.job_task
    VALIDATE CONSTRAINT fk_job_task_instance_tenant_date;

-- 分区引用表会在每个父表叶子分区上创建对应的子外键。
-- 启动检查会遍历目录中的每一行，因此也要验证这些自动生成的约束。

DO $$
DECLARE
    partition_fk RECORD;
BEGIN
    FOR partition_fk IN
        SELECT constraint_row.conname
          FROM pg_constraint constraint_row
         WHERE constraint_row.conrelid = 'batch.job_task'::regclass
           AND constraint_row.contype = 'f'
           AND NOT constraint_row.convalidated
           AND pg_get_constraintdef(constraint_row.oid)
               LIKE 'FOREIGN KEY (tenant_id, job_instance_id, job_instance_biz_date)%'
    LOOP
        EXECUTE format(
            'ALTER TABLE batch.job_task VALIDATE CONSTRAINT %I',
            partition_fk.conname);
    END LOOP;
END;
$$;

-- 全新数据库中的表为空；只有所有迁移完成后才会写入归档数据。

ALTER TABLE archive.job_task_archive
    -- squawk-ignore adding-required-field
    ADD COLUMN job_instance_biz_date DATE NOT NULL;

COMMENT ON COLUMN batch.job_task.job_instance_biz_date IS
    '从所属 JobInstance 复制的业务日分区键，用于由数据库原生外键保障租户隔离。';

COMMENT ON COLUMN archive.job_task_archive.job_instance_biz_date IS
    '归档 Task 时保留所属 JobInstance 的业务日身份。';
