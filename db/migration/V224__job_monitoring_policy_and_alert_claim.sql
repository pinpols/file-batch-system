-- 作业级监控策略及异步告警的幂等 claim 记录。
CREATE TABLE batch.job_monitoring_policy (
    id                              BIGSERIAL PRIMARY KEY,
    tenant_id                       VARCHAR(64) NOT NULL,
    job_definition_id               BIGINT NOT NULL REFERENCES batch.job_definition(id) ON DELETE CASCADE,
    soft_runtime_seconds            INTEGER NOT NULL DEFAULT 0,
    start_grace_seconds             INTEGER NOT NULL DEFAULT 0,
    completion_deadline_seconds     INTEGER NOT NULL DEFAULT 0,
    updated_by                      VARCHAR(64),
    created_at                      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completion_deadline_updated_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_job_monitoring_policy_tenant_definition
        UNIQUE (tenant_id, job_definition_id),
    CONSTRAINT ck_job_monitoring_policy_soft_runtime
        CHECK (soft_runtime_seconds >= 0),
    CONSTRAINT ck_job_monitoring_policy_start_grace
        CHECK (start_grace_seconds >= 0),
    CONSTRAINT ck_job_monitoring_policy_completion_deadline
        CHECK (completion_deadline_seconds >= 0)
);

COMMENT ON TABLE batch.job_monitoring_policy IS
    '按租户配置的作业监控阈值。零表示关闭对应监控；硬执行超时仍由 job_definition.timeout_seconds 控制。';
COMMENT ON COLUMN batch.job_monitoring_policy.soft_runtime_seconds IS
    '运行耗时告警阈值；触发告警不会中断作业执行。';
COMMENT ON COLUMN batch.job_monitoring_policy.start_grace_seconds IS
    '从计划触发时刻到 Worker 启动的允许延迟；仅实例具有 scheduledAt 时适用。';
COMMENT ON COLUMN batch.job_monitoring_policy.completion_deadline_seconds IS
    '完成期限时长；定时实例从 scheduledAt 起算，非定时实例从创建时刻起算。';
COMMENT ON COLUMN batch.job_monitoring_policy.completion_deadline_updated_at IS
    '完成期限策略最近一次变更时间；普通作业编辑和其他监控阈值变更不会重置完成过晚的基准。';

CREATE TABLE batch.job_monitoring_alert_claim (
    tenant_id       VARCHAR(64) NOT NULL,
    job_instance_id BIGINT NOT NULL,
    violation_type  VARCHAR(32) NOT NULL,
    claimed_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_job_monitoring_alert_claim
        PRIMARY KEY (tenant_id, job_instance_id, violation_type),
    CONSTRAINT ck_job_monitoring_alert_claim_type
        CHECK (violation_type IN ('RUNNING_TOO_LONG', 'NOT_STARTED', 'COMPLETED_LATE'))
);

COMMENT ON TABLE batch.job_monitoring_alert_claim IS
    '异步作业监控告警的幂等 claim；与 job_instance 生命周期写入隔离。';
COMMENT ON COLUMN batch.job_monitoring_alert_claim.violation_type IS
    '告警违规类型；与租户和作业实例共同构成幂等 claim 主键。';
