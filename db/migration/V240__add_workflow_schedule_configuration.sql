-- 为 Workflow 定义增加调度字段，并从显式关联的 Job 复制初始配置。
-- 顶层调度归 Workflow 定义；复制仅按 single_job_definition_id 关联。

ALTER TABLE batch.workflow_definition
    ADD COLUMN schedule_type VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN schedule_expr VARCHAR(256),
    ADD COLUMN timezone VARCHAR(64),
    ADD COLUMN trigger_mode VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN calendar_code VARCHAR(128),
    ADD COLUMN depends_on_job_code VARCHAR(128);

ALTER TABLE batch.workflow_definition
    ADD CONSTRAINT ck_workflow_definition_schedule_type
        CHECK (schedule_type IN ('CRON', 'FIXED_RATE', 'MANUAL', 'EVENT', 'ONE_TIME')) NOT VALID,
    ADD CONSTRAINT ck_workflow_definition_trigger_mode
        CHECK (trigger_mode IN ('SCHEDULED', 'API', 'MANUAL', 'EVENT', 'MIXED')) NOT VALID,
    ADD CONSTRAINT ck_workflow_definition_cron_expr_required
        CHECK (
            schedule_type NOT IN ('CRON', 'FIXED_RATE')
                OR NULLIF(BTRIM(schedule_expr), '') IS NOT NULL
        ) NOT VALID;

ALTER TABLE batch.workflow_definition
    VALIDATE CONSTRAINT ck_workflow_definition_schedule_type,
    VALIDATE CONSTRAINT ck_workflow_definition_trigger_mode,
    VALIDATE CONSTRAINT ck_workflow_definition_cron_expr_required;

-- 仅按 single_job_definition_id 复制关联 Job 的初始配置，不按 Job code 推断目标 Workflow。

UPDATE batch.workflow_definition wd
SET schedule_type = jd.schedule_type,
    schedule_expr = jd.schedule_expr,
    timezone = jd.timezone,
    trigger_mode = jd.trigger_mode,
    calendar_code = jd.calendar_code,
    depends_on_job_code = jd.depends_on_job_code,
    updated_at = CURRENT_TIMESTAMP
FROM batch.job_definition jd
WHERE jd.tenant_id = wd.tenant_id
  AND jd.id = wd.single_job_definition_id;

CREATE INDEX idx_workflow_definition_scheduled
    ON batch.workflow_definition (tenant_id, schedule_type, trigger_mode, workflow_code)
    WHERE enabled = TRUE AND schedule_type IN ('CRON', 'FIXED_RATE');

COMMENT ON COLUMN batch.workflow_definition.schedule_type IS
    'Workflow 级调度类型；顶层触发调度由 Workflow 负责。';
COMMENT ON COLUMN batch.workflow_definition.schedule_expr IS
    'Workflow 触发调度器使用的 Workflow 级调度表达式。';
COMMENT ON COLUMN batch.workflow_definition.timezone IS
    '用于解析 Workflow 触发时刻和业务日期的 IANA 时区。';
COMMENT ON COLUMN batch.workflow_definition.trigger_mode IS
    'Workflow 触发入口模式（SCHEDULED/API/MANUAL/EVENT/MIXED）。';
COMMENT ON COLUMN batch.workflow_definition.calendar_code IS
    '用于解析 Workflow 触发业务日期的租户业务日历。';
COMMENT ON COLUMN batch.workflow_definition.depends_on_job_code IS
    'Workflow 调度的上游 Job 就绪依赖；引用同一业务日内的上游作业。';
