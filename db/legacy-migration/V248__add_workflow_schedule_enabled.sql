-- Workflow 调度的暂停/恢复与 Job 执行资格及
-- Workflow 定义可用性相互独立。保留现有已启用的调度定义。

ALTER TABLE batch.workflow_definition
    ADD COLUMN schedule_enabled BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN batch.workflow_definition.schedule_enabled IS
    'Workflow 触发调度开关；独立于 workflow_definition.enabled 和 job_definition.enabled。';
