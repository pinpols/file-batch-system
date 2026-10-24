-- 在现有定义版本历史中保留 Workflow 调度语义。
-- 旧快照中的历史调度值未被记录，因此保持为 NULL。

ALTER TABLE batch.workflow_definition_version
    ADD COLUMN schedule_type VARCHAR(32),
    ADD COLUMN schedule_expr VARCHAR(256),
    ADD COLUMN timezone VARCHAR(64),
    ADD COLUMN trigger_mode VARCHAR(32),
    ADD COLUMN calendar_code VARCHAR(128),
    ADD COLUMN depends_on_job_code VARCHAR(128);

ALTER TABLE archive.workflow_definition_version_archive
    ADD COLUMN schedule_type VARCHAR(32),
    ADD COLUMN schedule_expr VARCHAR(256),
    ADD COLUMN timezone VARCHAR(64),
    ADD COLUMN trigger_mode VARCHAR(32),
    ADD COLUMN calendar_code VARCHAR(128),
    ADD COLUMN depends_on_job_code VARCHAR(128);

COMMENT ON COLUMN archive.workflow_definition_version_archive.schedule_type IS
    '完整 Workflow 定义版本中冻结的调度类型；V241 之前写入的快照为空。';
COMMENT ON COLUMN archive.workflow_definition_version_archive.schedule_expr IS
    '完整 Workflow 定义版本中冻结的调度表达式；V241 之前写入的快照为空。';
COMMENT ON COLUMN archive.workflow_definition_version_archive.timezone IS
    '完整 Workflow 定义版本中冻结的调度时区；V241 之前写入的快照为空。';
COMMENT ON COLUMN archive.workflow_definition_version_archive.trigger_mode IS
    '完整 Workflow 定义版本中冻结的触发模式；V241 之前写入的快照为空。';
COMMENT ON COLUMN archive.workflow_definition_version_archive.calendar_code IS
    '完整 Workflow 定义版本中冻结的业务日历；V241 之前写入的快照为空。';
COMMENT ON COLUMN archive.workflow_definition_version_archive.depends_on_job_code IS
    '完整 Workflow 定义版本中冻结的旧版 Job 依赖；V241 之前写入的快照为空。';

COMMENT ON COLUMN batch.workflow_definition_version.schedule_type IS
    '完整 Workflow 定义版本中冻结的调度类型；V241 之前写入的快照为空。';
COMMENT ON COLUMN batch.workflow_definition_version.schedule_expr IS
    '完整 Workflow 定义版本中冻结的调度表达式；V241 之前写入的快照为空。';
COMMENT ON COLUMN batch.workflow_definition_version.timezone IS
    '完整 Workflow 定义版本中冻结的调度时区；V241 之前写入的快照为空。';
COMMENT ON COLUMN batch.workflow_definition_version.trigger_mode IS
    '完整 Workflow 定义版本中冻结的触发模式；V241 之前写入的快照为空。';
COMMENT ON COLUMN batch.workflow_definition_version.calendar_code IS
    '完整 Workflow 定义版本中冻结的业务日历；V241 之前写入的快照为空。';
COMMENT ON COLUMN batch.workflow_definition_version.depends_on_job_code IS
    '完整 Workflow 定义版本中冻结的旧版 Job 依赖；V241 之前写入的快照为空。';
