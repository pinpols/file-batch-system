-- 为 Workflow 节点运行记录持久化跳过原因类别和说明。

ALTER TABLE batch.workflow_node_run
    ADD COLUMN IF NOT EXISTS skip_reason_code VARCHAR(64),
    ADD COLUMN IF NOT EXISTS skip_reason VARCHAR(1024);

ALTER TABLE archive.workflow_node_run_archive
    ADD COLUMN IF NOT EXISTS skip_reason_code VARCHAR(64),
    ADD COLUMN IF NOT EXISTS skip_reason VARCHAR(1024);

COMMENT ON COLUMN batch.workflow_node_run.skip_reason_code IS
    'Workflow 节点被 SKIPPED 的稳定原因类别，例如 CONDITION_NOT_MATCHED 或 OPERATOR_SKIPPED。';
COMMENT ON COLUMN batch.workflow_node_run.skip_reason IS
    '操作员填写的可读原因，或 Workflow 节点被跳过的简要说明。';
