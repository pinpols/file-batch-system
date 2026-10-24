ALTER TABLE batch.workflow_node_run
    ADD COLUMN IF NOT EXISTS skip_reason_code VARCHAR(64),
    ADD COLUMN IF NOT EXISTS skip_reason VARCHAR(1024);

ALTER TABLE archive.workflow_node_run_archive
    ADD COLUMN IF NOT EXISTS skip_reason_code VARCHAR(64),
    ADD COLUMN IF NOT EXISTS skip_reason VARCHAR(1024);

COMMENT ON COLUMN batch.workflow_node_run.skip_reason_code IS
    'Stable reason category for a SKIPPED Workflow node, such as CONDITION_NOT_MATCHED or OPERATOR_SKIPPED.';
COMMENT ON COLUMN batch.workflow_node_run.skip_reason IS
    'Human-readable operator reason or concise explanation for why the Workflow node was skipped.';
