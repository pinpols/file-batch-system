-- 将用户发起的重放操作与各个可执行的日期会话分开持久化。
-- 操作记录作为审计根保留；日期会话仍是实际执行单元。

CREATE TABLE batch.replay_operation (
    id                          BIGSERIAL PRIMARY KEY,
    tenant_id                   VARCHAR(64)  NOT NULL,
    operation_key               UUID         NOT NULL,
    operation_type              VARCHAR(32)  NOT NULL,
    status                      VARCHAR(32)  NOT NULL,
    calendar_code               VARCHAR(128),
    from_date                   DATE,
    through_date                DATE,
    scope                       VARCHAR(32),
    execution_mode              VARCHAR(32),
    candidate_source            VARCHAR(32),
    scope_payload               JSONB,
    result_policy               VARCHAR(64),
    config_version_policy       VARCHAR(64),
    config_version              INTEGER,
    source_workflow_run_id      BIGINT,
    source_workflow_node_run_id BIGINT,
    source_job_instance_id      BIGINT,
    target_request_id           VARCHAR(128),
    target_workflow_run_id      BIGINT,
    request_hash                VARCHAR(64)  NOT NULL,
    snapshot_hash               VARCHAR(64)  NOT NULL,
    snapshot_payload            JSONB        NOT NULL,
    reason                      VARCHAR(1024) NOT NULL,
    requested_by                VARCHAR(128) NOT NULL,
    approved_by                 VARCHAR(128),
    total_count                 INTEGER      NOT NULL DEFAULT 0,
    succeeded_count             INTEGER      NOT NULL DEFAULT 0,
    failed_count                INTEGER      NOT NULL DEFAULT 0,
    in_flight_count             INTEGER      NOT NULL DEFAULT 0,
    started_at                  TIMESTAMPTZ,
    completed_at                TIMESTAMPTZ,
    trace_id                    VARCHAR(128),
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_replay_operation_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uk_replay_operation_identity UNIQUE (tenant_id, operation_key),
    CONSTRAINT ck_replay_operation_type
        CHECK (operation_type IN ('BATCH_DAY_RANGE', 'WORKFLOW_NODE', 'WORKFLOW_RUN')),
    CONSTRAINT ck_replay_operation_status
        CHECK (status IN ('PENDING_APPROVAL', 'RUNNING', 'SUCCEEDED', 'PARTIAL_FAILED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_replay_operation_day_range
        CHECK (
            (operation_type = 'BATCH_DAY_RANGE'
                AND calendar_code IS NOT NULL
                AND from_date IS NOT NULL
                AND through_date IS NOT NULL
                AND through_date >= from_date)
            OR (operation_type <> 'BATCH_DAY_RANGE'
                AND from_date IS NULL
                AND through_date IS NULL)
        ),
    CONSTRAINT ck_replay_operation_workflow_target
        CHECK (
            (operation_type = 'BATCH_DAY_RANGE'
                AND source_workflow_run_id IS NULL
                AND source_workflow_node_run_id IS NULL)
            OR (operation_type = 'WORKFLOW_NODE'
                AND source_workflow_run_id IS NOT NULL
                AND source_workflow_node_run_id IS NOT NULL
                AND source_job_instance_id IS NOT NULL)
            OR (operation_type = 'WORKFLOW_RUN'
                AND source_workflow_run_id IS NOT NULL
                AND source_workflow_node_run_id IS NULL
                AND source_job_instance_id IS NULL)
        ),
    CONSTRAINT ck_replay_operation_nonnegative_counts
        CHECK (total_count >= 0 AND succeeded_count >= 0 AND failed_count >= 0 AND in_flight_count >= 0)
);

CREATE INDEX idx_replay_operation_tenant_status_created
    ON batch.replay_operation (tenant_id, status, created_at DESC);

CREATE INDEX idx_replay_operation_tenant_source_workflow
    ON batch.replay_operation (tenant_id, source_workflow_run_id, created_at DESC)
    WHERE source_workflow_run_id IS NOT NULL;

ALTER TABLE batch.batch_day_replay_session
    ADD COLUMN replay_operation_id BIGINT;

ALTER TABLE archive.batch_day_replay_session_archive
    ADD COLUMN replay_operation_id BIGINT;

ALTER TABLE batch.batch_day_replay_session
    -- squawk-ignore constraint-missing-not-valid
    ADD CONSTRAINT fk_replay_session_operation_tenant
        FOREIGN KEY (tenant_id, replay_operation_id)
        REFERENCES batch.replay_operation (tenant_id, id)
        MATCH SIMPLE
        ON UPDATE NO ACTION
        ON DELETE NO ACTION;

CREATE INDEX idx_replay_session_operation
    ON batch.batch_day_replay_session (tenant_id, replay_operation_id, biz_date)
    WHERE replay_operation_id IS NOT NULL;

CREATE TABLE batch.replay_operation_preview_token (
    tenant_id       VARCHAR(64)  NOT NULL,
    token_hash      VARCHAR(64)  NOT NULL,
    operation_key   UUID         NOT NULL,
    request_hash    VARCHAR(64)  NOT NULL,
    snapshot_hash   VARCHAR(64)  NOT NULL,
    snapshot_payload JSONB       NOT NULL,
    expires_at      TIMESTAMPTZ  NOT NULL,
    consumed_at     TIMESTAMPTZ,
    operation_id    BIGINT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_replay_operation_preview_token PRIMARY KEY (tenant_id, token_hash),
    CONSTRAINT uk_replay_operation_preview_identity UNIQUE (tenant_id, operation_key),
    CONSTRAINT fk_replay_operation_preview_operation_tenant
        FOREIGN KEY (tenant_id, operation_id)
        REFERENCES batch.replay_operation (tenant_id, id)
        MATCH SIMPLE
        ON UPDATE NO ACTION
        ON DELETE NO ACTION
);

CREATE INDEX idx_replay_operation_preview_expiry
    ON batch.replay_operation_preview_token (expires_at);

ALTER TABLE batch.replay_operation ENABLE ROW LEVEL SECURITY;
ALTER TABLE batch.replay_operation FORCE ROW LEVEL SECURITY;
CREATE POLICY replay_operation_tenant_isolation ON batch.replay_operation
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE batch.replay_operation_preview_token ENABLE ROW LEVEL SECURITY;
ALTER TABLE batch.replay_operation_preview_token FORCE ROW LEVEL SECURITY;
CREATE POLICY replay_operation_preview_tenant_isolation
    ON batch.replay_operation_preview_token
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

COMMENT ON TABLE batch.replay_operation IS
    'ADR-049 重放操作及其审计根记录；日期会话独立执行并保留该操作身份。';
COMMENT ON COLUMN batch.replay_operation.operation_key IS
    '由预览生成并对客户端可见的稳定操作身份；同租户以相同键重复提交时返回同一操作。';
COMMENT ON COLUMN batch.replay_operation.snapshot_payload IS
    '审批和恢复所需的冻结来源、配置、结果版本、目标及副作用预览。';
COMMENT ON COLUMN batch.batch_day_replay_session.replay_operation_id IS
    '日期区间重放所属的 ADR-049 父操作；现有单日会话 API 创建的记录为 NULL。';
COMMENT ON COLUMN archive.batch_day_replay_session_archive.replay_operation_id IS
    '子日期会话归档时保留的 ADR-049 父操作身份。';
COMMENT ON TABLE batch.replay_operation_preview_token IS
    '短时有效、租户隔离的一次性预览凭证；仅保存令牌摘要和冻结的预览快照。';
COMMENT ON COLUMN batch.replay_operation.operation_type IS
    '重放范围：日期区间、指定 Workflow 节点，或整个来源 WorkflowRun。';
COMMENT ON COLUMN batch.replay_operation.status IS
    '此重放操作持久化的审批、执行及终态。';
COMMENT ON COLUMN batch.replay_operation.execution_mode IS
    '重放操作选择的执行模式，包括正式重放或 dry-run。';
COMMENT ON COLUMN batch.replay_operation.scope_payload IS
    '此重放操作冻结的范围专属请求身份和参数。';
COMMENT ON COLUMN batch.replay_operation.result_policy IS
    '控制重放新结果如何版本化并生效的策略。';
COMMENT ON COLUMN batch.replay_operation.config_version_policy IS
    '选择重放所用冻结 Workflow 和 Job 配置版本的策略。';
COMMENT ON COLUMN batch.replay_operation.config_version IS
    '所选策略要求明确指定版本时记录的目标配置版本。';
COMMENT ON COLUMN batch.replay_operation.request_hash IS
    '规范化业务请求字段的指纹；用于检测操作键被不同输入重复使用。';
COMMENT ON COLUMN batch.replay_operation.snapshot_hash IS
    '将已批准操作绑定到经过审核的预览快照的哈希值。';
COMMENT ON COLUMN batch.replay_operation.trace_id IS
    '可观测性关联标识；不参与操作身份或请求去重。';
COMMENT ON COLUMN batch.replay_operation_preview_token.token_hash IS
    '一次性预览密钥的 SHA-256 摘要；原始令牌仅返回给调用方。';
COMMENT ON COLUMN batch.replay_operation_preview_token.request_hash IS
    '与此预览凭证绑定的规范化请求字段指纹。';
COMMENT ON COLUMN batch.replay_operation_preview_token.snapshot_hash IS
    '用于在凭证消费前验证已审核预览快照未发生变化的哈希值。';
COMMENT ON COLUMN batch.replay_operation_preview_token.snapshot_payload IS
    '与此凭证绑定、并在一次性提交前校验的预览快照。';
COMMENT ON INDEX batch.idx_replay_operation_tenant_status_created IS
    '支持按租户查询重放操作状态列表，并按创建时间排序。';
COMMENT ON INDEX batch.idx_replay_operation_tenant_source_workflow IS
    '支持按租户及来源 WorkflowRun 查询重放操作。';
COMMENT ON INDEX batch.idx_replay_session_operation IS
    '支持按租户查询父重放操作下的日期会话列表。';
COMMENT ON INDEX batch.idx_replay_operation_preview_expiry IS
    '支持有界清理已过期的一次性重放预览凭证。';
