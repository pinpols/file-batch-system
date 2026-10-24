-- Bind each hot JobPartition to the tenant and business-date identity of its partitioned JobInstance.
-- V173 removed the original FK because job_instance's primary key includes biz_date.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM batch.job_partition partition
          LEFT JOIN batch.job_instance instance
            ON instance.id = partition.job_instance_id
         GROUP BY partition.tenant_id, partition.job_instance_id
        HAVING COUNT(instance.id) <> 1
            OR BOOL_OR(instance.tenant_id IS DISTINCT FROM partition.tenant_id)
    ) THEN
        RAISE EXCEPTION 'job_partition contains an orphan or cross-tenant JobInstance reference';
    END IF;
END;
$$;

ALTER TABLE batch.job_partition
    ADD COLUMN job_instance_biz_date DATE;

UPDATE batch.job_partition partition
   SET job_instance_biz_date = instance.biz_date
  FROM batch.job_instance instance
 WHERE instance.id = partition.job_instance_id;

ALTER TABLE batch.job_partition
    ADD CONSTRAINT ck_job_partition_instance_biz_date_not_null
        CHECK (job_instance_biz_date IS NOT NULL)
        NOT VALID;

ALTER TABLE batch.job_partition
    VALIDATE CONSTRAINT ck_job_partition_instance_biz_date_not_null;

CREATE UNIQUE INDEX uk_job_instance_tenant_id_biz_date
    ON batch.job_instance (tenant_id, id, biz_date);

CREATE INDEX idx_job_partition_tenant_instance_date
    ON batch.job_partition (tenant_id, job_instance_id, job_instance_biz_date);

ALTER TABLE batch.job_partition
    ADD CONSTRAINT fk_job_partition_instance_tenant_date
        FOREIGN KEY (tenant_id, job_instance_id, job_instance_biz_date)
        REFERENCES batch.job_instance (tenant_id, id, biz_date)
        ON DELETE NO ACTION
        NOT VALID;

ALTER TABLE batch.job_partition
    VALIDATE CONSTRAINT fk_job_partition_instance_tenant_date;

-- PostgreSQL creates one referencing-side FK constraint per referenced JobInstance leaf
-- partition. Validate those generated constraints too; startup's NotValidConstraintGuard
-- checks the complete catalog, not only the named parent constraint.
DO $$
DECLARE
    partition_fk RECORD;
BEGIN
    FOR partition_fk IN
        SELECT constraint_row.conname
          FROM pg_constraint constraint_row
         WHERE constraint_row.conrelid = 'batch.job_partition'::regclass
           AND constraint_row.contype = 'f'
           AND NOT constraint_row.convalidated
           AND pg_get_constraintdef(constraint_row.oid)
               LIKE 'FOREIGN KEY (tenant_id, job_instance_id, job_instance_biz_date)%'
    LOOP
        EXECUTE format(
            'ALTER TABLE batch.job_partition VALIDATE CONSTRAINT %I',
            partition_fk.conname);
    END LOOP;
END;
$$;

ALTER TABLE archive.job_partition_archive
    ADD COLUMN job_instance_biz_date DATE;

COMMENT ON COLUMN batch.job_partition.job_instance_biz_date IS
    'Business-date partition key copied from the owning JobInstance for native tenant-safe FK enforcement';
