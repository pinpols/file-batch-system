BEGIN;

CREATE TEMP TABLE seed_cleanup_job_instance_ids(id BIGINT PRIMARY KEY) ON COMMIT DROP;
CREATE TEMP TABLE seed_cleanup_job_task_ids(id BIGINT PRIMARY KEY) ON COMMIT DROP;
CREATE TEMP TABLE seed_cleanup_job_partition_ids(id BIGINT PRIMARY KEY) ON COMMIT DROP;
CREATE TEMP TABLE seed_cleanup_pipeline_instance_ids(id BIGINT PRIMARY KEY) ON COMMIT DROP;
CREATE TEMP TABLE seed_cleanup_file_record_ids(id BIGINT PRIMARY KEY) ON COMMIT DROP;
CREATE TEMP TABLE seed_cleanup_outbox_event_ids(id BIGINT PRIMARY KEY) ON COMMIT DROP;
CREATE TEMP TABLE seed_cleanup_job_definition_ids(id BIGINT PRIMARY KEY) ON COMMIT DROP;

INSERT INTO seed_cleanup_job_instance_ids(id)
SELECT DISTINCT id
FROM batch.job_instance
WHERE trigger_request_id IN (
    SELECT id
    FROM batch.trigger_request
    WHERE request_id LIKE :'pattern'
       OR job_code LIKE :'pattern'
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

INSERT INTO seed_cleanup_job_definition_ids(id)
SELECT id
FROM batch.job_definition
WHERE job_code LIKE :'pattern'
ON CONFLICT DO NOTHING;

DELETE FROM batch.pipeline_step_run
WHERE pipeline_instance_id IN (SELECT id FROM seed_cleanup_pipeline_instance_ids);

DELETE FROM batch.file_audit_log
WHERE file_id IN (SELECT id FROM seed_cleanup_file_record_ids);

DELETE FROM batch.file_dispatch_record
WHERE pipeline_instance_id IN (SELECT id FROM seed_cleanup_pipeline_instance_ids)
   OR file_id IN (SELECT id FROM seed_cleanup_file_record_ids);

DELETE FROM batch.file_record
WHERE id IN (SELECT id FROM seed_cleanup_file_record_ids);

DELETE FROM batch.pipeline_instance
WHERE id IN (SELECT id FROM seed_cleanup_pipeline_instance_ids);

DELETE FROM batch.workflow_node_run
WHERE workflow_run_id IN (
    SELECT id
    FROM batch.workflow_run
    WHERE related_job_instance_id IN (SELECT id FROM seed_cleanup_job_instance_ids)
);

DELETE FROM batch.workflow_run
WHERE related_job_instance_id IN (SELECT id FROM seed_cleanup_job_instance_ids);

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

DELETE FROM batch.trigger_request
WHERE request_id LIKE :'pattern'
   OR job_code LIKE :'pattern';

DELETE FROM batch.trigger_runtime_state
WHERE job_definition_id IN (SELECT id FROM seed_cleanup_job_definition_ids);

DELETE FROM batch.workflow_node
WHERE node_code = 'SEEDVAL_PROBE';

DELETE FROM batch.pipeline_step_definition
WHERE pipeline_definition_id IN (
    SELECT id
    FROM batch.pipeline_definition
    WHERE job_code LIKE :'pattern'
);

DELETE FROM batch.pipeline_definition
WHERE job_code LIKE :'pattern';

DELETE FROM batch.job_definition
WHERE id IN (SELECT id FROM seed_cleanup_job_definition_ids);

COMMIT;
