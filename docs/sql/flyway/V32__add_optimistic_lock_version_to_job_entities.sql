-- Orchestrator 乐观锁：job_instance 在 V4 已含 version；partition / task 在此补齐。
-- 与历史 orchestrator 模块内增量脚本语义一致（IF NOT EXISTS，可重复执行）。

ALTER TABLE batch.job_instance
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE batch.job_partition
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE batch.job_task
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
