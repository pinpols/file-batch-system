-- 在每个不可变 Workflow 定义快照中保留暂停/恢复状态。

ALTER TABLE batch.workflow_definition_version
    ADD COLUMN schedule_enabled BOOLEAN;

ALTER TABLE archive.workflow_definition_version_archive
    ADD COLUMN schedule_enabled BOOLEAN;

COMMENT ON COLUMN batch.workflow_definition_version.schedule_enabled IS
    '此版本冻结的 Workflow 触发调度开关；V249 之前写入的快照为空。';

COMMENT ON COLUMN archive.workflow_definition_version_archive.schedule_enabled IS
    '此版本冻结的 Workflow 触发调度开关；V249 之前写入的快照为空。';
