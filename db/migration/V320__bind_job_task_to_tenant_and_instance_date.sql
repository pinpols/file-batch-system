-- Restore the Task -> partitioned JobInstance reference removed by V173.
-- Fresh-database deployment applies this before any runtime data or seeds exist.
ALTER TABLE batch.job_task
    -- Empty fresh-database table; no legacy rows or online backfill are supported.
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

-- A partitioned referenced table has a matching child FK per parent leaf.
-- Startup checks every catalog row, so validate those generated constraints too.
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

-- Empty fresh-database table; archive writes start only after all migrations finish.
ALTER TABLE archive.job_task_archive
    -- squawk-ignore adding-required-field
    ADD COLUMN job_instance_biz_date DATE NOT NULL;

COMMENT ON COLUMN batch.job_task.job_instance_biz_date IS
    'Business-date partition key copied from the owning JobInstance for native tenant-safe FK enforcement';

COMMENT ON COLUMN archive.job_task_archive.job_instance_biz_date IS
    'Business-date identity of the owning JobInstance retained with the archived Task';
