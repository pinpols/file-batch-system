-- ADR-026：整批量日 dry-run 会话模型。
-- 正式 batch_day_instance 的三元唯一身份保持不变；演练复用 replay session/entry 聚合。

ALTER TABLE batch.batch_day_replay_session
    ADD COLUMN IF NOT EXISTS execution_mode VARCHAR(24) NOT NULL DEFAULT 'REPLAY',
    ADD COLUMN IF NOT EXISTS candidate_source VARCHAR(32) NOT NULL DEFAULT 'EXISTING_INSTANCES';

ALTER TABLE archive.batch_day_replay_session_archive
    ADD COLUMN IF NOT EXISTS execution_mode VARCHAR(24) NOT NULL DEFAULT 'REPLAY',
    ADD COLUMN IF NOT EXISTS candidate_source VARCHAR(32) NOT NULL DEFAULT 'EXISTING_INSTANCES';

ALTER TABLE batch.batch_day_replay_entry
    ADD COLUMN IF NOT EXISTS plan_snapshot JSONB;

ALTER TABLE archive.batch_day_replay_entry_archive
    ADD COLUMN IF NOT EXISTS plan_snapshot JSONB;

CREATE INDEX IF NOT EXISTS idx_result_version_dry_run_retention
    ON batch.result_version (generated_at, id)
    WHERE status = 'DRY_RUN';

ALTER TABLE batch.batch_day_replay_session
    DROP CONSTRAINT IF EXISTS ck_replay_session_execution_mode;
ALTER TABLE batch.batch_day_replay_session
    ADD CONSTRAINT ck_replay_session_execution_mode
    CHECK (execution_mode IN ('REPLAY', 'DRY_RUN')) NOT VALID;

ALTER TABLE batch.batch_day_replay_session
    DROP CONSTRAINT IF EXISTS ck_replay_session_candidate_source;
ALTER TABLE batch.batch_day_replay_session
    ADD CONSTRAINT ck_replay_session_candidate_source
    CHECK (candidate_source IN ('EXISTING_INSTANCES', 'SCHEDULE_PLAN')) NOT VALID;

ALTER TABLE batch.batch_day_replay_session
    DROP CONSTRAINT IF EXISTS ck_replay_session_result_policy;
ALTER TABLE batch.batch_day_replay_session
    ADD CONSTRAINT ck_replay_session_result_policy
    CHECK (result_policy IN (
        'CREATE_NEW_VERSION', 'KEEP_BOTH', 'MANUAL_CONFIRM_EFFECTIVE', 'DRY_RUN_ONLY')) NOT VALID;

ALTER TABLE batch.batch_day_replay_session
    DROP CONSTRAINT IF EXISTS ck_replay_session_dry_run_contract;
ALTER TABLE batch.batch_day_replay_session
    ADD CONSTRAINT ck_replay_session_dry_run_contract
    CHECK (
        execution_mode <> 'DRY_RUN'
        OR (scope <> 'OUTPUTS_ONLY' AND result_policy = 'DRY_RUN_ONLY')) NOT VALID;

ALTER TABLE batch.batch_day_replay_entry
    DROP CONSTRAINT IF EXISTS ck_replay_entry_candidate_shape;
ALTER TABLE batch.batch_day_replay_entry
    ADD CONSTRAINT ck_replay_entry_candidate_shape
    CHECK (
        source_instance_id IS NOT NULL
        OR result_version_id IS NOT NULL
        OR plan_snapshot IS NOT NULL) NOT VALID;

ALTER TABLE batch.batch_day_replay_entry
    VALIDATE CONSTRAINT ck_replay_entry_candidate_shape;

ALTER TABLE batch.batch_day_replay_session
    VALIDATE CONSTRAINT ck_replay_session_execution_mode;
ALTER TABLE batch.batch_day_replay_session
    VALIDATE CONSTRAINT ck_replay_session_candidate_source;
ALTER TABLE batch.batch_day_replay_session
    VALIDATE CONSTRAINT ck_replay_session_result_policy;
ALTER TABLE batch.batch_day_replay_session
    VALIDATE CONSTRAINT ck_replay_session_dry_run_contract;

COMMENT ON COLUMN batch.batch_day_replay_session.execution_mode IS
    'REPLAY=正式重放；DRY_RUN=无业务副作用整日演练。';
COMMENT ON COLUMN batch.batch_day_replay_session.candidate_source IS
    'EXISTING_INSTANCES=历史实例；SCHEDULE_PLAN=按调度计划生成候选。';
COMMENT ON COLUMN batch.batch_day_replay_entry.plan_snapshot IS
    'SCHEDULE_PLAN 候选的不可变计划快照；历史实例候选为空。';

COMMENT ON COLUMN archive.batch_day_replay_session_archive.execution_mode IS
    '归档镜像：REPLAY 或 DRY_RUN。';
COMMENT ON COLUMN archive.batch_day_replay_session_archive.candidate_source IS
    '归档镜像：EXISTING_INSTANCES 或 SCHEDULE_PLAN。';
COMMENT ON COLUMN archive.batch_day_replay_entry_archive.plan_snapshot IS
    '归档镜像：计划来源候选的不可变快照。';
COMMENT ON INDEX batch.idx_result_version_dry_run_retention IS
    '支持按独立保留期批量归档 DRY_RUN 结果。';
