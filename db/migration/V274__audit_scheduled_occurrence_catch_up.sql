-- 对单次被拒绝定时触发的人工恢复，与一般 Workflow 启动区分开。

CREATE TABLE batch.scheduled_occurrence_catch_up_audit (
    id                    BIGSERIAL PRIMARY KEY,
    tenant_id             VARCHAR(64)  NOT NULL,
    workflow_definition_id BIGINT       NOT NULL,
    scheduled_fire_time   TIMESTAMPTZ  NOT NULL,
    biz_date              DATE         NOT NULL,
    request_id            VARCHAR(128) NOT NULL,
    requested_by          VARCHAR(128) NOT NULL,
    reason                VARCHAR(1024) NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uk_scheduled_occurrence_catch_up
        UNIQUE (tenant_id, workflow_definition_id, scheduled_fire_time),
    CONSTRAINT fk_scheduled_occurrence_catch_up_occurrence
        FOREIGN KEY (tenant_id, workflow_definition_id, scheduled_fire_time)
        REFERENCES batch.scheduled_occurrence
            (tenant_id, workflow_definition_id, scheduled_fire_time),
    CONSTRAINT fk_scheduled_occurrence_catch_up_request
        FOREIGN KEY (tenant_id, request_id)
        REFERENCES batch.trigger_request (tenant_id, request_id)
);

COMMENT ON TABLE batch.scheduled_occurrence_catch_up_audit IS
    '将被拒绝的定时触发与显式 CATCH_UP 请求关联起来的不可变操作审计。';
