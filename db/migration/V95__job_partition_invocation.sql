-- ADR-014: partition CLAIM invocation id — renew/report optional match + stale-worker isolation

ALTER TABLE batch.job_partition
    ADD COLUMN IF NOT EXISTS current_invocation_id VARCHAR(64);

ALTER TABLE batch.job_partition
    ADD COLUMN IF NOT EXISTS invocation_started_at TIMESTAMPTZ;

ALTER TABLE archive.job_partition_archive
    ADD COLUMN IF NOT EXISTS current_invocation_id VARCHAR(64);

ALTER TABLE archive.job_partition_archive
    ADD COLUMN IF NOT EXISTS invocation_started_at TIMESTAMPTZ;
