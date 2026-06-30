-- ADR-020/P1 replay impact preview:同一 session 内允许同 jobCode 的多个来源实例或版本。
-- 旧唯一键 (session_id, tenant_id, job_code) 会在 insertBatch 的 ON CONFLICT 中吞掉同 jobCode 后续 entry,
-- 导致 preview 看到的影响范围和实际执行不一致。

DROP INDEX IF EXISTS batch.uk_replay_entry_session_job;

CREATE UNIQUE INDEX IF NOT EXISTS uk_replay_entry_session_source_instance
    ON batch.batch_day_replay_entry (session_id, tenant_id, source_instance_id)
    WHERE source_instance_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_replay_entry_session_result_version
    ON batch.batch_day_replay_entry (session_id, tenant_id, result_version_id)
    WHERE result_version_id IS NOT NULL;

COMMENT ON INDEX batch.uk_replay_entry_session_source_instance IS
    '同一 replay session 内同一个 source_instance_id 只能物化一次;允许同 jobCode 多实例';

COMMENT ON INDEX batch.uk_replay_entry_session_result_version IS
    '同一 replay session 内同一个 result_version_id 只能 promote 一次;允许同 jobCode 多版本';
