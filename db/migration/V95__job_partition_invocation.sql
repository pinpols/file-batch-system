-- ADR-014：分区 CLAIM 调用身份——续租/回报可选匹配，用于隔离过期 Worker。

ALTER TABLE batch.job_partition
    ADD COLUMN IF NOT EXISTS current_invocation_id VARCHAR(64);

ALTER TABLE batch.job_partition
    ADD COLUMN IF NOT EXISTS invocation_started_at TIMESTAMPTZ;

ALTER TABLE archive.job_partition_archive
    ADD COLUMN IF NOT EXISTS current_invocation_id VARCHAR(64);

ALTER TABLE archive.job_partition_archive
    ADD COLUMN IF NOT EXISTS invocation_started_at TIMESTAMPTZ;
