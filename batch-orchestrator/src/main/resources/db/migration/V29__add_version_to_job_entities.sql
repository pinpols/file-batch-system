-- Add optimistic lock version columns for orchestrator runtime entities.
-- Safe to run multiple times (IF NOT EXISTS).

ALTER TABLE batch.job_instance
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE batch.job_partition
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE batch.job_task
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

