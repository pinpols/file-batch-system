-- =========================================================
-- V59 - Add optimistic lock version to quota_runtime_state
-- =========================================================

ALTER TABLE batch.quota_runtime_state
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
