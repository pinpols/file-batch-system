-- 危险：仅在停止生产方后，清理明确归本 SDK 租户/作业/受管 Workflow 夹具所有的记录。
BEGIN;

CREATE TEMP TABLE sdk_e2e_workflow_ids ON COMMIT DROP AS
SELECT workflow.id
FROM batch.workflow_definition workflow
JOIN batch.job_definition job
  ON job.tenant_id = workflow.tenant_id
 AND job.id = workflow.single_job_definition_id
WHERE workflow.tenant_id = :'tenant_id'
  AND job.job_code = :'job_code'
  AND workflow.workflow_code = 'JOB-WF-' || job.id;

CREATE TEMP TABLE sdk_e2e_workflow_run_ids ON COMMIT DROP AS
SELECT id FROM batch.workflow_run
WHERE tenant_id=:'tenant_id'
  AND workflow_definition_id IN (SELECT id FROM sdk_e2e_workflow_ids);

UPDATE batch.job_instance
SET instance_status = 'CANCELLED'
WHERE tenant_id = :'tenant_id'
  AND job_code = :'job_code'
  AND instance_status = 'RUNNING';

DELETE FROM batch.job_task
WHERE tenant_id = :'tenant_id'
  AND job_instance_id IN (
      SELECT id
      FROM batch.job_instance
      WHERE tenant_id = :'tenant_id'
        AND job_code = :'job_code'
  );

DELETE FROM batch.job_partition
WHERE tenant_id = :'tenant_id'
  AND job_instance_id IN (
      SELECT id
      FROM batch.job_instance
      WHERE tenant_id = :'tenant_id'
        AND job_code = :'job_code'
  );

DELETE FROM batch.workflow_node_run node_run
USING batch.workflow_run workflow_run
WHERE node_run.workflow_run_id = workflow_run.id
  AND workflow_run.tenant_id = :'tenant_id'
  AND workflow_run.workflow_definition_id IN (SELECT id FROM sdk_e2e_workflow_ids);

DELETE FROM batch.workflow_run
WHERE tenant_id = :'tenant_id'
  AND workflow_definition_id IN (SELECT id FROM sdk_e2e_workflow_ids);

DELETE FROM batch.job_instance
WHERE tenant_id = :'tenant_id'
  AND job_code = :'job_code';

DELETE FROM batch.trigger_outbox_event event
WHERE event.tenant_id = :'tenant_id'
  AND EXISTS (
      SELECT 1
      FROM batch.trigger_request request
      WHERE request.tenant_id = event.tenant_id
        AND request.request_id = event.request_id
        AND (request.workflow_definition_id IN (SELECT id FROM sdk_e2e_workflow_ids)
             OR request.parent_workflow_run_id IN (SELECT id FROM sdk_e2e_workflow_run_ids))
  );

DELETE FROM batch.job_dependency_waiting_launch waiting
USING batch.trigger_request request
WHERE waiting.tenant_id=:'tenant_id' AND waiting.tenant_id=request.tenant_id
  AND waiting.request_id=request.request_id
  AND (request.workflow_definition_id IN (SELECT id FROM sdk_e2e_workflow_ids)
             OR request.parent_workflow_run_id IN (SELECT id FROM sdk_e2e_workflow_run_ids));

DELETE FROM batch.workflow_launch_wait waiting
USING batch.trigger_request request
WHERE waiting.tenant_id=:'tenant_id' AND waiting.tenant_id=request.tenant_id
  AND waiting.request_id=request.request_id
  AND (request.workflow_definition_id IN (SELECT id FROM sdk_e2e_workflow_ids)
             OR request.parent_workflow_run_id IN (SELECT id FROM sdk_e2e_workflow_run_ids));

DELETE FROM batch.batch_day_waiting_launch waiting
USING batch.trigger_request request
WHERE waiting.tenant_id=:'tenant_id' AND waiting.tenant_id=request.tenant_id
  AND waiting.request_id=request.request_id
  AND (request.workflow_definition_id IN (SELECT id FROM sdk_e2e_workflow_ids)
             OR request.parent_workflow_run_id IN (SELECT id FROM sdk_e2e_workflow_run_ids));

DELETE FROM batch.trigger_request
WHERE tenant_id = :'tenant_id'
  AND (workflow_definition_id IN (SELECT id FROM sdk_e2e_workflow_ids)
       OR parent_workflow_run_id IN (SELECT id FROM sdk_e2e_workflow_run_ids));

DELETE FROM batch.workflow_definition_version
WHERE tenant_id = :'tenant_id'
  AND workflow_definition_id IN (SELECT id FROM sdk_e2e_workflow_ids);

DELETE FROM batch.workflow_edge
WHERE tenant_id = :'tenant_id'
  AND workflow_definition_id IN (SELECT id FROM sdk_e2e_workflow_ids);

DELETE FROM batch.workflow_node
WHERE tenant_id = :'tenant_id'
  AND workflow_definition_id IN (SELECT id FROM sdk_e2e_workflow_ids);

DELETE FROM batch.workflow_definition
WHERE tenant_id = :'tenant_id'
  AND id IN (SELECT id FROM sdk_e2e_workflow_ids);

DELETE FROM batch.job_definition
WHERE tenant_id = :'tenant_id'
  AND job_code = :'job_code';

DELETE FROM batch.resource_queue
WHERE tenant_id = :'tenant_id'
  AND queue_code = :'queue_code'
  AND NOT EXISTS (
      SELECT 1
      FROM batch.job_definition
      WHERE tenant_id = :'tenant_id'
        AND queue_code = :'queue_code'
  );

DELETE FROM batch.worker_registry
WHERE tenant_id = :'tenant_id'
  AND worker_code = :'worker_code';

DELETE FROM batch.api_key
WHERE tenant_id = :'tenant_id'
  AND key_name = :'worker_code';

COMMIT;
