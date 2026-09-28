-- =========================================================
-- V59 - 为 quota_runtime_state 增加乐观锁版本号
-- =========================================================

ALTER TABLE batch.quota_runtime_state
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
