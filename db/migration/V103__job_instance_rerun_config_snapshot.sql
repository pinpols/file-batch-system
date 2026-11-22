-- =========================================================
-- V103：增加重跑结果与配置版本追踪信息
-- =========================================================

ALTER TABLE batch.job_instance
    ADD COLUMN IF NOT EXISTS job_definition_version INTEGER,
    ADD COLUMN IF NOT EXISTS rerun_policy_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE archive.job_instance_archive
    ADD COLUMN IF NOT EXISTS job_definition_version INTEGER,
    ADD COLUMN IF NOT EXISTS rerun_policy_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb;

COMMENT ON COLUMN batch.job_instance.job_definition_version IS
    '创建此实例时冻结的 job_definition.version。';
COMMENT ON COLUMN batch.job_instance.rerun_policy_snapshot IS
    '重跑语义快照，包括 runAttempt、parentInstanceId、triggerType、重跑标记和原因。';

COMMENT ON COLUMN archive.job_instance_archive.job_definition_version IS
    'V103 镜像字段：从 batch.job_instance 复制的 job_definition.version 快照。';
COMMENT ON COLUMN archive.job_instance_archive.rerun_policy_snapshot IS
    'V103 镜像字段：从 batch.job_instance 复制的重跑策略快照。';
