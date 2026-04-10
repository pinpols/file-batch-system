-- 数据归档/清理策略：定义各类运行态数据的保留时间和归档规则。
CREATE TABLE IF NOT EXISTS batch.archive_policy (
    id                BIGSERIAL       PRIMARY KEY,
    tenant_id         VARCHAR(64)     NOT NULL,
    target_table      VARCHAR(64)     NOT NULL,          -- job_instance, workflow_run, job_partition, file_record, audit_log
    retention_days    INTEGER         NOT NULL DEFAULT 90,
    archive_enabled   BOOLEAN         NOT NULL DEFAULT FALSE,
    cleanup_enabled   BOOLEAN         NOT NULL DEFAULT FALSE,
    batch_size        INTEGER         NOT NULL DEFAULT 1000,
    description       VARCHAR(512),
    created_by        VARCHAR(64),
    updated_by        VARCHAR(64),
    created_at        TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_archive_policy_tenant_table UNIQUE (tenant_id, target_table),
    CONSTRAINT ck_archive_policy_table CHECK (target_table IN (
        'job_instance','workflow_run','job_partition','file_record',
        'audit_log','outbox_event','outbox_delivery_log','webhook_delivery_log'
    ))
);
