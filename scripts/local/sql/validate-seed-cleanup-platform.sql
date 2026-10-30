-- DANGER: 删除 validate-seed 平台夹具数据。仅限隔离本地测试库，并按 fixture pattern 精确筛选。
BEGIN;


CREATE TEMP TABLE seed_cleanup_job_instance_ids(id BIGINT PRIMARY KEY) ON COMMIT DROP;

CREATE TEMP TABLE seed_cleanup_job_task_ids(id BIGINT PRIMARY KEY) ON COMMIT DROP;

CREATE TEMP TABLE seed_cleanup_job_partition_ids(id BIGINT PRIMARY KEY) ON COMMIT DROP;

CREATE TEMP TABLE seed_cleanup_pipeline_instance_ids(id BIGINT PRIMARY KEY) ON COMMIT DROP;

CREATE TEMP TABLE seed_cleanup_file_record_ids(id BIGINT PRIMARY KEY) ON COMMIT DROP;

CREATE TEMP TABLE seed_cleanup_outbox_event_ids(id BIGINT PRIMARY KEY) ON COMMIT DROP;

CREATE TEMP TABLE seed_cleanup_job_definition_ids(id BIGINT PRIMARY KEY) ON COMMIT DROP;

CREATE TEMP TABLE seed_cleanup_workflow_definition_ids(id BIGINT PRIMARY KEY) ON COMMIT DROP;

CREATE TEMP TABLE seed_cleanup_workflow_run_ids(id BIGINT PRIMARY KEY) ON COMMIT DROP;


INSERT INTO seed_cleanup_job_definition_ids(id)
SELECT id
FROM batch.job_definition
WHERE job_code LIKE :'pattern'
ON CONFLICT DO NOTHING;


INSERT INTO seed_cleanup_workflow_definition_ids(id)
SELECT id
FROM batch.workflow_definition
WHERE workflow_code LIKE :'pattern'
   OR single_job_definition_id IN (SELECT id FROM seed_cleanup_job_definition_ids)
   OR (created_by LIKE :'pattern'
       AND description LIKE 'Local simulation managed single-job Workflow fixture %')
ON CONFLICT DO NOTHING;


INSERT INTO seed_cleanup_workflow_run_ids(id)
SELECT workflow_run.id
FROM batch.workflow_run workflow_run
LEFT JOIN batch.trigger_request trigger_request
  ON trigger_request.tenant_id = workflow_run.tenant_id
 AND trigger_request.id = workflow_run.trigger_request_id
WHERE trigger_request.request_id LIKE :'pattern'
   OR workflow_run.workflow_definition_id IN (SELECT id FROM seed_cleanup_workflow_definition_ids)
ON CONFLICT DO NOTHING;


INSERT INTO seed_cleanup_job_instance_ids(id)
SELECT DISTINCT workflow_node_run.job_instance_id
FROM batch.workflow_node_run workflow_node_run
WHERE workflow_node_run.workflow_run_id IN (SELECT id FROM seed_cleanup_workflow_run_ids)
  AND workflow_node_run.job_instance_id IS NOT NULL
ON CONFLICT DO NOTHING;


INSERT INTO seed_cleanup_job_instance_ids(id)
SELECT DISTINCT id
FROM batch.job_instance
WHERE trigger_request_id IN (
    SELECT id
    FROM batch.trigger_request
    WHERE request_id LIKE :'pattern'
       OR job_code LIKE :'pattern'
       OR parent_workflow_run_id IN (SELECT id FROM seed_cleanup_workflow_run_ids)
)
   OR job_code LIKE :'pattern'
ON CONFLICT DO NOTHING;


INSERT INTO seed_cleanup_job_task_ids(id)
SELECT id
FROM batch.job_task
WHERE job_instance_id IN (SELECT id FROM seed_cleanup_job_instance_ids)
ON CONFLICT DO NOTHING;


INSERT INTO seed_cleanup_job_partition_ids(id)
SELECT id
FROM batch.job_partition
WHERE job_instance_id IN (SELECT id FROM seed_cleanup_job_instance_ids)
ON CONFLICT DO NOTHING;


INSERT INTO seed_cleanup_pipeline_instance_ids(id)
SELECT id
FROM batch.pipeline_instance
WHERE related_job_instance_id IN (SELECT id FROM seed_cleanup_job_instance_ids)
ON CONFLICT DO NOTHING;


INSERT INTO seed_cleanup_file_record_ids(id)
SELECT id
FROM batch.file_record
WHERE source_ref IN (
    SELECT instance_no
    FROM batch.job_instance
    WHERE id IN (SELECT id FROM seed_cleanup_job_instance_ids)
)
   OR source_ref LIKE :'pattern'
   OR file_code LIKE :'pattern'
ON CONFLICT DO NOTHING;


INSERT INTO seed_cleanup_file_record_ids(id)
SELECT file_id
FROM batch.pipeline_instance
WHERE id IN (SELECT id FROM seed_cleanup_pipeline_instance_ids)
  AND file_id IS NOT NULL
ON CONFLICT DO NOTHING;


INSERT INTO seed_cleanup_outbox_event_ids(id)
SELECT id
FROM batch.outbox_event
WHERE aggregate_id::text IN (
    SELECT id::text FROM seed_cleanup_job_instance_ids
    UNION ALL
    SELECT id::text FROM seed_cleanup_job_task_ids
    UNION ALL
    SELECT id::text FROM seed_cleanup_job_partition_ids
)
ON CONFLICT DO NOTHING;


DELETE FROM batch.pipeline_step_run
WHERE pipeline_instance_id IN (SELECT id FROM seed_cleanup_pipeline_instance_ids);


DELETE FROM batch.pipeline_progress
WHERE pipeline_instance_id IN (SELECT id FROM seed_cleanup_pipeline_instance_ids);


DELETE FROM batch.file_audit_log
WHERE file_id IN (SELECT id FROM seed_cleanup_file_record_ids);


DELETE FROM batch.file_dispatch_record
WHERE pipeline_instance_id IN (SELECT id FROM seed_cleanup_pipeline_instance_ids)
   OR file_id IN (SELECT id FROM seed_cleanup_file_record_ids);


DELETE FROM batch.pipeline_instance
WHERE id IN (SELECT id FROM seed_cleanup_pipeline_instance_ids);


DELETE FROM batch.workflow_node_run
WHERE workflow_run_id IN (SELECT id FROM seed_cleanup_workflow_run_ids)
   OR job_instance_id IN (SELECT id FROM seed_cleanup_job_instance_ids);


DELETE FROM batch.workflow_run
WHERE id IN (SELECT id FROM seed_cleanup_workflow_run_ids)
   OR related_job_instance_id IN (SELECT id FROM seed_cleanup_job_instance_ids);


DELETE FROM batch.job_step_instance
WHERE job_instance_id IN (SELECT id FROM seed_cleanup_job_instance_ids)
   OR job_task_id IN (SELECT id FROM seed_cleanup_job_task_ids);


DELETE FROM batch.dead_letter_task
WHERE (source_type = 'JOB_PARTITION' AND source_id IN (SELECT id FROM seed_cleanup_job_partition_ids))
   OR (source_type = 'JOB_TASK' AND source_id IN (SELECT id FROM seed_cleanup_job_task_ids))
   OR (source_type = 'JOB_INSTANCE' AND source_id IN (SELECT id FROM seed_cleanup_job_instance_ids));


DELETE FROM batch.retry_schedule
WHERE (related_type = 'JOB_PARTITION' AND related_id IN (SELECT id FROM seed_cleanup_job_partition_ids))
   OR (related_type = 'JOB_TASK' AND related_id IN (SELECT id FROM seed_cleanup_job_task_ids))
   OR (related_type = 'JOB_INSTANCE' AND related_id IN (SELECT id FROM seed_cleanup_job_instance_ids));


DELETE FROM batch.job_task
WHERE id IN (SELECT id FROM seed_cleanup_job_task_ids);


DELETE FROM batch.job_partition
WHERE id IN (SELECT id FROM seed_cleanup_job_partition_ids);


DELETE FROM batch.file_record
WHERE id IN (SELECT id FROM seed_cleanup_file_record_ids);


DELETE FROM batch.job_execution_log
WHERE job_instance_id IN (SELECT id FROM seed_cleanup_job_instance_ids);


DELETE FROM batch.event_delivery_log
WHERE outbox_event_id IN (SELECT id FROM seed_cleanup_outbox_event_ids);


DELETE FROM batch.outbox_event
WHERE id IN (SELECT id FROM seed_cleanup_outbox_event_ids);


DELETE FROM batch.job_instance
WHERE id IN (SELECT id FROM seed_cleanup_job_instance_ids);


DELETE FROM batch.trigger_outbox_event
WHERE request_id LIKE :'pattern';


-- 与删除父请求使用完全相同的夹具筛选条件；不执行全局等待记录清理。
DELETE FROM batch.job_dependency_waiting_launch waiting
USING batch.trigger_request request
WHERE waiting.tenant_id=request.tenant_id AND waiting.request_id=request.request_id
  AND (request.request_id LIKE :'pattern' OR request.job_code LIKE :'pattern'
       OR request.parent_workflow_run_id IN (SELECT id FROM seed_cleanup_workflow_run_ids));


DELETE FROM batch.workflow_launch_wait waiting
USING batch.trigger_request request
WHERE waiting.tenant_id=request.tenant_id AND waiting.request_id=request.request_id
  AND (request.request_id LIKE :'pattern' OR request.job_code LIKE :'pattern'
       OR request.parent_workflow_run_id IN (SELECT id FROM seed_cleanup_workflow_run_ids));


DELETE FROM batch.batch_day_waiting_launch waiting
USING batch.trigger_request request
WHERE waiting.tenant_id=request.tenant_id AND waiting.request_id=request.request_id
  AND (request.request_id LIKE :'pattern' OR request.job_code LIKE :'pattern'
       OR request.parent_workflow_run_id IN (SELECT id FROM seed_cleanup_workflow_run_ids));


DELETE FROM batch.trigger_request
WHERE request_id LIKE :'pattern'
   OR job_code LIKE :'pattern'
   OR parent_workflow_run_id IN (SELECT id FROM seed_cleanup_workflow_run_ids);


DELETE FROM batch.workflow_node
WHERE workflow_definition_id IN (SELECT id FROM seed_cleanup_workflow_definition_ids);


DELETE FROM batch.workflow_edge
WHERE workflow_definition_id IN (SELECT id FROM seed_cleanup_workflow_definition_ids);


DELETE FROM batch.workflow_definition
WHERE id IN (SELECT id FROM seed_cleanup_workflow_definition_ids);


DELETE FROM batch.job_definition
WHERE id IN (SELECT id FROM seed_cleanup_job_definition_ids);


COMMIT;

-- DANGER: 删除 validate-seed 平台夹具数据。仅限隔离本地测试库，并按 fixture pattern 精确筛选。
