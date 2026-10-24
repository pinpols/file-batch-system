-- Quartz 预期触发记录用于日结检查。Trigger 仍是唯一的调度权威；
-- 此表只是可重建的投影，不会创建 trigger_request 记录。

CREATE TABLE batch.scheduled_occurrence (
    id                    BIGSERIAL PRIMARY KEY,
    tenant_id             VARCHAR(64)  NOT NULL,
    workflow_definition_id BIGINT       NOT NULL,
    workflow_code          VARCHAR(128) NOT NULL,
    schedule_version      INTEGER      NOT NULL,
    calendar_code         VARCHAR(128),
    scheduled_fire_time   TIMESTAMPTZ  NOT NULL,
    biz_date              DATE,
    occurrence_status     VARCHAR(32)  NOT NULL DEFAULT 'PLANNED',
    request_id            VARCHAR(128),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_scheduled_occurrence_fire UNIQUE
        (tenant_id, workflow_definition_id, scheduled_fire_time),
    CONSTRAINT fk_scheduled_occurrence_workflow
        FOREIGN KEY (tenant_id, workflow_definition_id)
        REFERENCES batch.workflow_definition (tenant_id, id),
    CONSTRAINT ck_scheduled_occurrence_status CHECK (occurrence_status IN (
        'PLANNED', 'REQUESTED', 'WAITING_READINESS', 'MISFIRE_PENDING',
        'SKIPPED_CALENDAR', 'MISFIRE_SKIPPED', 'READINESS_TIMEOUT',
        'CLOSED_DAY_REJECTED', 'SUPERSEDED'
    ))
);

CREATE INDEX idx_scheduled_occurrence_day_status
    ON batch.scheduled_occurrence (tenant_id, calendar_code, biz_date, occurrence_status);

CREATE INDEX idx_scheduled_occurrence_workflow_version
    ON batch.scheduled_occurrence
       (tenant_id, workflow_definition_id, schedule_version, scheduled_fire_time);

COMMENT ON TABLE batch.scheduled_occurrence IS
    'Quartz 计划触发的投影记录，用于核对预期工作；属于 Trigger 控制面状态，不是请求或执行记录。';
COMMENT ON COLUMN batch.scheduled_occurrence.scheduled_fire_time IS
    '原始 Quartz 计划触发身份，精确到 epoch 毫秒；与定时请求去重键一致。';
COMMENT ON COLUMN batch.scheduled_occurrence.biz_date IS
    '由共享 Trigger 日历解析器计算；NULL 表示日历解析结果为 SKIP。';
COMMENT ON COLUMN batch.scheduled_occurrence.schedule_version IS
    '生成此 Quartz 触发投影时使用的 Workflow 调度版本。';
COMMENT ON COLUMN batch.scheduled_occurrence.occurrence_status IS
    '预期触发的生命周期状态，与 TriggerRequest 和 WorkflowRun 状态相互独立。';
