-- 为作业实例记录已取消和已终止的分区计数。

ALTER TABLE batch.job_instance
    ADD COLUMN cancelled_partition_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN terminated_partition_count INTEGER NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_job_instance_cancelled_partition_count
        CHECK (cancelled_partition_count >= 0) NOT VALID,
    ADD CONSTRAINT ck_job_instance_terminated_partition_count
        CHECK (terminated_partition_count >= 0) NOT VALID;

ALTER TABLE batch.job_instance
    VALIDATE CONSTRAINT ck_job_instance_cancelled_partition_count,
    VALIDATE CONSTRAINT ck_job_instance_terminated_partition_count;

ALTER TABLE archive.job_instance_archive
    ADD COLUMN cancelled_partition_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN terminated_partition_count INTEGER NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_job_instance_archive_cancelled_partition_count
        CHECK (cancelled_partition_count >= 0) NOT VALID,
    ADD CONSTRAINT ck_job_instance_archive_terminated_partition_count
        CHECK (terminated_partition_count >= 0) NOT VALID;

ALTER TABLE archive.job_instance_archive
    VALIDATE CONSTRAINT ck_job_instance_archive_cancelled_partition_count,
    VALIDATE CONSTRAINT ck_job_instance_archive_terminated_partition_count;

COMMENT ON COLUMN batch.job_instance.cancelled_partition_count IS
    '以 CANCELLED 状态完成的分区数量。';
COMMENT ON COLUMN batch.job_instance.terminated_partition_count IS
    '以 TERMINATED 状态完成的分区数量。';
COMMENT ON COLUMN archive.job_instance_archive.cancelled_partition_count IS
    '以 CANCELLED 状态完成的分区数量。';
COMMENT ON COLUMN archive.job_instance_archive.terminated_partition_count IS
    '以 TERMINATED 状态完成的分区数量。';
