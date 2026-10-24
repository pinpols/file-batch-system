-- Persist launch requests that reached Orchestrator before all declared upstream results were effective.
CREATE TABLE batch.job_dependency_waiting_launch (
    id                    BIGSERIAL PRIMARY KEY,
    tenant_id             VARCHAR(64)  NOT NULL,
    request_id            VARCHAR(128) NOT NULL,
    job_code              VARCHAR(128) NOT NULL,
    biz_date              DATE         NOT NULL,
    dependency_snapshot   JSONB        NOT NULL,
    launch_payload        JSONB        NOT NULL,
    wait_status           VARCHAR(24)  NOT NULL DEFAULT 'WAITING',
    attempt_count         INTEGER      NOT NULL DEFAULT 0,
    last_not_ready_reason VARCHAR(512),
    last_checked_at       TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    released_at           TIMESTAMPTZ,
    CONSTRAINT uk_job_dependency_wait_request UNIQUE (tenant_id, request_id),
    CONSTRAINT ck_job_dependency_wait_status
        CHECK (wait_status IN ('WAITING', 'RELEASED', 'CANCELLED')),
    CONSTRAINT ck_job_dependency_wait_attempt CHECK (attempt_count >= 0)
);

CREATE INDEX idx_job_dependency_wait_due
    ON batch.job_dependency_waiting_launch (tenant_id, wait_status, updated_at, id);

ALTER TABLE batch.job_dependency_waiting_launch ENABLE ROW LEVEL SECURITY;
ALTER TABLE batch.job_dependency_waiting_launch FORCE ROW LEVEL SECURITY;
CREATE POLICY job_dependency_wait_tenant_isolation ON batch.job_dependency_waiting_launch
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

COMMENT ON TABLE batch.job_dependency_waiting_launch IS
    '持久化尚未满足作业上游就绪条件的 Launch 请求；保留原业务日、依赖规则快照和完整请求参数供恢复器重放';
COMMENT ON COLUMN batch.job_dependency_waiting_launch.dependency_snapshot IS
    '首次进入等待时的上游作业编码快照，恢复时不因配置变更漂移';
COMMENT ON COLUMN batch.job_dependency_waiting_launch.launch_payload IS
    '完整 LaunchRequest JSON 快照，重启及事件重投后用于幂等恢复';
COMMENT ON COLUMN batch.job_dependency_waiting_launch.wait_status IS
    '依赖请求生命周期：WAITING 等待上游，RELEASED 已释放，CANCELLED 已取消';
