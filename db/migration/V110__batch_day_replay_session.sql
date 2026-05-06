-- =====================================================================
-- V110: 批量日维度重放（ADR-020）
-- =====================================================================
-- 背景:
--   §14.3.2 设计层缺口"批量日维度重放治理"。运维场景"重放整个 bizDate"
--   高频但目前不存在；只能写脚本循环 POST /jobs/{id}/rerun，无审批
--   /进度/版本一致性。
--
-- 决策（ADR-020 §决策）:
--   batch_day_replay_session 一等聚合：描述某 (tenant, calendarCode,
--   bizDate) 子集的重放请求 + 进度 + 结果；session 内每个 job 用
--   batch_day_replay_entry 跟踪。
--   scope: ALL / ALL_FAILED / SUBSET_JOB_CODES / OUTPUTS_ONLY。
--   状态机: PENDING_APPROVAL → RUNNING → SUCCEEDED / PARTIAL_FAILED
--                              / CANCELLED。
--   不变量: 同 (tenant, calendarCode, bizDate) 同时只能有 1 个 active
--   session（PENDING_APPROVAL / RUNNING）。
--
-- 强依赖 ADR-017: result_version 主模型；OUTPUTS_ONLY scope 直接走
--   result_version promote；其它 scope rerun 后由 worker 上报 outputs
--   写新 result_version。
-- =====================================================================

CREATE TABLE IF NOT EXISTS batch.batch_day_replay_session (
    id                      BIGSERIAL PRIMARY KEY,
    tenant_id               VARCHAR(64)  NOT NULL,
    calendar_code           VARCHAR(128) NOT NULL,
    biz_date                DATE         NOT NULL,
    scope                   VARCHAR(32)  NOT NULL,
    scope_payload           JSONB,
    result_policy           VARCHAR(64)  NOT NULL,
    config_version_policy   VARCHAR(64)  NOT NULL,
    config_version          INTEGER,
    reason                  VARCHAR(1024) NOT NULL,
    approval_id             BIGINT,
    status                  VARCHAR(32)  NOT NULL,
    total_count             INTEGER      NOT NULL DEFAULT 0,
    succeeded_count         INTEGER      NOT NULL DEFAULT 0,
    failed_count            INTEGER      NOT NULL DEFAULT 0,
    in_flight_count         INTEGER      NOT NULL DEFAULT 0,
    requested_by            VARCHAR(128) NOT NULL,
    approved_by             VARCHAR(128),
    started_at              TIMESTAMPTZ,
    completed_at            TIMESTAMPTZ,
    trace_id                VARCHAR(128),
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE batch.batch_day_replay_session DROP CONSTRAINT IF EXISTS ck_replay_session_scope;
ALTER TABLE batch.batch_day_replay_session ADD CONSTRAINT ck_replay_session_scope
    CHECK (scope IN ('ALL', 'ALL_FAILED', 'SUBSET_JOB_CODES', 'OUTPUTS_ONLY'));

ALTER TABLE batch.batch_day_replay_session DROP CONSTRAINT IF EXISTS ck_replay_session_status;
ALTER TABLE batch.batch_day_replay_session ADD CONSTRAINT ck_replay_session_status
    CHECK (status IN ('PENDING_APPROVAL', 'RUNNING', 'SUCCEEDED', 'PARTIAL_FAILED', 'CANCELLED'));

-- ADR-020 不变量：同 (tenant, calendarCode, bizDate) 同时只能有 1 个 active session
CREATE UNIQUE INDEX IF NOT EXISTS uk_replay_session_active
    ON batch.batch_day_replay_session (tenant_id, calendar_code, biz_date)
    WHERE status IN ('PENDING_APPROVAL', 'RUNNING');

CREATE INDEX IF NOT EXISTS idx_replay_session_tenant_status
    ON batch.batch_day_replay_session (tenant_id, status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_replay_session_tenant_bizdate
    ON batch.batch_day_replay_session (tenant_id, calendar_code, biz_date, created_at DESC);

COMMENT ON TABLE batch.batch_day_replay_session IS
    'ADR-020 批量日维度重放聚合; 同 (tenant, calendarCode, bizDate) 至多 1 个 active session';
COMMENT ON COLUMN batch.batch_day_replay_session.scope IS
    'ALL: 当日所有 SUCCESS/FAILED; ALL_FAILED: 当日 FAILED/PARTIAL_FAILED; SUBSET_JOB_CODES: scope_payload 列出 jobCode; OUTPUTS_ONLY: promote 历史版本不重跑';
COMMENT ON COLUMN batch.batch_day_replay_session.scope_payload IS
    'SUBSET_JOB_CODES 时存 {"jobCodes": [...]}, OUTPUTS_ONLY 时存 {"versionIds": [...]}';
COMMENT ON COLUMN batch.batch_day_replay_session.result_policy IS
    '同 RerunRequest.resultPolicy: CREATE_NEW_VERSION / KEEP_BOTH / MANUAL_CONFIRM_EFFECTIVE';

-- =====================================================================
-- batch_day_replay_entry: session 内每个 job 的重跑入口
-- =====================================================================
CREATE TABLE IF NOT EXISTS batch.batch_day_replay_entry (
    id                  BIGSERIAL PRIMARY KEY,
    session_id          BIGINT       NOT NULL REFERENCES batch.batch_day_replay_session(id),
    tenant_id           VARCHAR(64)  NOT NULL,
    job_code            VARCHAR(128) NOT NULL,
    source_instance_id  BIGINT,
    rerun_instance_id   BIGINT,
    status              VARCHAR(32)  NOT NULL,
    failure_reason      VARCHAR(1024),
    started_at          TIMESTAMPTZ,
    finished_at         TIMESTAMPTZ,
    result_version_id   BIGINT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE batch.batch_day_replay_entry DROP CONSTRAINT IF EXISTS ck_replay_entry_status;
ALTER TABLE batch.batch_day_replay_entry ADD CONSTRAINT ck_replay_entry_status
    CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED'));

CREATE UNIQUE INDEX IF NOT EXISTS uk_replay_entry_session_job
    ON batch.batch_day_replay_entry (session_id, tenant_id, job_code);

CREATE INDEX IF NOT EXISTS idx_replay_entry_session_status
    ON batch.batch_day_replay_entry (session_id, status);

CREATE INDEX IF NOT EXISTS idx_replay_entry_rerun_instance
    ON batch.batch_day_replay_entry (rerun_instance_id) WHERE rerun_instance_id IS NOT NULL;

COMMENT ON TABLE batch.batch_day_replay_entry IS
    'ADR-020 batch_day_replay_session 子项: 每个 jobCode 一行, 跟踪 source→rerun→result_version 链路';

-- =====================================================================
-- archive 镜像（按 §archive 冷表对齐红线）
-- =====================================================================
CREATE TABLE IF NOT EXISTS archive.batch_day_replay_session_archive
    (LIKE batch.batch_day_replay_session INCLUDING ALL);

CREATE TABLE IF NOT EXISTS archive.batch_day_replay_entry_archive
    (LIKE batch.batch_day_replay_entry INCLUDING ALL);

COMMENT ON TABLE archive.batch_day_replay_session_archive IS
    'V110 archive mirror of batch.batch_day_replay_session';
COMMENT ON TABLE archive.batch_day_replay_entry_archive IS
    'V110 archive mirror of batch.batch_day_replay_entry';
