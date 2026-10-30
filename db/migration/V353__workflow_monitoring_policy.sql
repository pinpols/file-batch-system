-- Workflow 根级软时限策略；与 Job 策略独立，阈值为零或截止钟点为空时关闭对应规则。

CREATE TABLE batch.workflow_monitoring_policy (
    tenant_id                       VARCHAR(64) NOT NULL,
    workflow_definition_id          BIGINT NOT NULL,
    soft_runtime_seconds            INTEGER NOT NULL DEFAULT 0,
    soft_runtime_severity           VARCHAR(16) NOT NULL DEFAULT 'WARN',
    start_grace_seconds             INTEGER NOT NULL DEFAULT 0,
    start_grace_severity             VARCHAR(16) NOT NULL DEFAULT 'WARN',
    completion_deadline_local_time  TIME,
    completion_deadline_day_offset  INTEGER NOT NULL DEFAULT 0,
    completion_deadline_severity    VARCHAR(16) NOT NULL DEFAULT 'WARN',
    policy_effective_at             TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at                      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_workflow_monitoring_policy PRIMARY KEY (tenant_id, workflow_definition_id),
    CONSTRAINT fk_workflow_monitoring_policy_definition
        FOREIGN KEY (tenant_id, workflow_definition_id)
        REFERENCES batch.workflow_definition (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_workflow_monitoring_policy_thresholds CHECK (
        soft_runtime_seconds >= 0 AND start_grace_seconds >= 0
        AND completion_deadline_day_offset IN (0, 1)),
    CONSTRAINT ck_workflow_monitoring_policy_severities CHECK (
        soft_runtime_severity IN ('WARN', 'ERROR', 'CRITICAL')
        AND start_grace_severity IN ('WARN', 'ERROR', 'CRITICAL')
        AND completion_deadline_severity IN ('WARN', 'ERROR', 'CRITICAL'))
);

ALTER TABLE batch.workflow_monitoring_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE batch.workflow_monitoring_policy FORCE ROW LEVEL SECURITY;
CREATE POLICY workflow_monitoring_policy_tenant_isolation ON batch.workflow_monitoring_policy
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

COMMENT ON TABLE batch.workflow_monitoring_policy IS
    'Workflow 根运行的软时限策略；不改变运行状态或硬超时，不继承为子 Job 策略。';
COMMENT ON COLUMN batch.workflow_monitoring_policy.tenant_id IS '策略所属租户。';
COMMENT ON COLUMN batch.workflow_monitoring_policy.workflow_definition_id IS '同租户 Workflow 定义身份。';
COMMENT ON COLUMN batch.workflow_monitoring_policy.soft_runtime_seconds IS '根运行开始后允许的总耗时秒数，包含编排等待；零为关闭。';
COMMENT ON COLUMN batch.workflow_monitoring_policy.soft_runtime_severity IS '根运行耗时超限的告警级别。';
COMMENT ON COLUMN batch.workflow_monitoring_policy.start_grace_seconds IS '真实计划或就绪资格时刻后的启动宽限秒数；零为关闭。';
COMMENT ON COLUMN batch.workflow_monitoring_policy.start_grace_severity IS '根运行开始过晚的告警级别。';
COMMENT ON COLUMN batch.workflow_monitoring_policy.completion_deadline_local_time IS '按 Workflow 时区解释的最晚完成钟点；空为关闭。';
COMMENT ON COLUMN batch.workflow_monitoring_policy.completion_deadline_day_offset IS '最晚完成日相对计划触发日的偏移，零为当日，一为次日。';
COMMENT ON COLUMN batch.workflow_monitoring_policy.completion_deadline_severity IS '根运行完成过晚的告警级别。';
COMMENT ON COLUMN batch.workflow_monitoring_policy.policy_effective_at IS '阈值或截止策略最近生效时刻；不补报此前已结束的历史运行。';
COMMENT ON COLUMN batch.workflow_monitoring_policy.created_at IS '策略首次保存时刻。';
COMMENT ON COLUMN batch.workflow_monitoring_policy.updated_at IS '策略最近保存时刻。';
