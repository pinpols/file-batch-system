-- Make each executable JOB node point to the Job Instance it owns. Keep nullable for old
-- TASK/FILE_STEP runs and historical rows; no FK is added because job_instance is partitioned.
ALTER TABLE batch.workflow_node_run
    ADD COLUMN job_instance_id BIGINT;

ALTER TABLE archive.workflow_node_run_archive
    ADD COLUMN job_instance_id BIGINT;

CREATE UNIQUE INDEX uk_workflow_node_run_job_instance
    ON batch.workflow_node_run (job_instance_id)
    WHERE job_instance_id IS NOT NULL;

COMMENT ON COLUMN batch.workflow_node_run.job_instance_id IS
    'Job Instance executing this Workflow JOB node; null for historical/unlinked and non-JOB nodes.';
COMMENT ON COLUMN archive.workflow_node_run_archive.job_instance_id IS
    'Archive mirror of batch.workflow_node_run.job_instance_id; null when no Job Instance link was recorded.';

-- Child Job launches carry only their owning Workflow node identity. Workflow is the orchestration
-- root; no synthetic parent Job, partition, or task participates in node completion.
ALTER TABLE batch.trigger_request
    ADD COLUMN parent_workflow_run_id BIGINT,
    ADD COLUMN parent_node_code VARCHAR(128);

COMMENT ON COLUMN batch.trigger_request.parent_workflow_run_id IS
    'Owning Workflow Run for an internal Job-node launch; null for top-level triggers.';
COMMENT ON COLUMN batch.trigger_request.parent_node_code IS
    'Owning Workflow JOB node code for an internal Job-node launch.';
