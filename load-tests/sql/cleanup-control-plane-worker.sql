BEGIN;

-- run_id is generated per profile and is the cleanup boundary. Do not pin this
-- fixture cleanup to default-tenant: fairness profiles intentionally use ta/tb/tc.
-- Trigger ingress stores the run id in trigger_request.request_id rather than
-- job_instance.params_snapshot. Resolve both forms once so cleanup covers the
-- direct orchestrator and normal Trigger API profiles alike.
CREATE TEMP TABLE p2_cleanup_job_instance_ids (
  id bigint PRIMARY KEY
) ON COMMIT DROP;

INSERT INTO p2_cleanup_job_instance_ids (id)
SELECT ji.id
FROM batch.job_instance ji
WHERE ji.params_snapshot::text LIKE ('%' || :'run_id' || '%')
UNION
SELECT tr.related_job_instance_id
FROM batch.trigger_request tr
WHERE tr.related_job_instance_id IS NOT NULL
  AND (
    tr.request_id LIKE ('%' || :'run_id' || '%')
    OR tr.dedup_key LIKE ('%' || :'run_id' || '%')
    OR tr.trace_id LIKE ('%' || :'run_id' || '%')
  );

WITH ji AS (
  SELECT id FROM p2_cleanup_job_instance_ids
),
jt AS (
  SELECT id FROM batch.job_task WHERE job_instance_id IN (SELECT id FROM ji)
),
jp AS (
  SELECT id FROM batch.job_partition WHERE job_instance_id IN (SELECT id FROM ji)
),
oe AS (
  SELECT id
  FROM batch.outbox_event
  WHERE (
      (aggregate_type = 'JOB_INSTANCE' AND aggregate_id IN (SELECT id FROM ji))
      OR (aggregate_type = 'JOB_PARTITION' AND aggregate_id IN (SELECT id FROM jp))
      OR (aggregate_type = 'JOB_TASK' AND aggregate_id IN (SELECT id FROM jt))
    )
)
DELETE FROM batch.event_outbox_retry
WHERE outbox_event_id IN (SELECT id FROM oe);

WITH ji AS (
  SELECT id FROM p2_cleanup_job_instance_ids
),
jt AS (
  SELECT id FROM batch.job_task WHERE job_instance_id IN (SELECT id FROM ji)
),
jp AS (
  SELECT id FROM batch.job_partition WHERE job_instance_id IN (SELECT id FROM ji)
),
oe AS (
  SELECT id
  FROM batch.outbox_event
  WHERE (
      (aggregate_type = 'JOB_INSTANCE' AND aggregate_id IN (SELECT id FROM ji))
      OR (aggregate_type = 'JOB_PARTITION' AND aggregate_id IN (SELECT id FROM jp))
      OR (aggregate_type = 'JOB_TASK' AND aggregate_id IN (SELECT id FROM jt))
    )
)
DELETE FROM batch.event_delivery_log
WHERE outbox_event_id IN (SELECT id FROM oe);

WITH ji AS (
  SELECT id FROM p2_cleanup_job_instance_ids
),
jt AS (
  SELECT id FROM batch.job_task WHERE job_instance_id IN (SELECT id FROM ji)
),
jp AS (
  SELECT id FROM batch.job_partition WHERE job_instance_id IN (SELECT id FROM ji)
)
DELETE FROM batch.outbox_event
WHERE (
    (aggregate_type = 'JOB_INSTANCE' AND aggregate_id IN (SELECT id FROM ji))
    OR (aggregate_type = 'JOB_PARTITION' AND aggregate_id IN (SELECT id FROM jp))
    OR (aggregate_type = 'JOB_TASK' AND aggregate_id IN (SELECT id FROM jt))
  );

WITH ji AS (
  SELECT id FROM p2_cleanup_job_instance_ids
),
jt AS (
  SELECT id FROM batch.job_task WHERE job_instance_id IN (SELECT id FROM ji)
)
DELETE FROM batch.job_step_instance WHERE job_task_id IN (SELECT id FROM jt);

WITH ji AS (
  SELECT id FROM p2_cleanup_job_instance_ids
)
DELETE FROM batch.pipeline_step_run
WHERE pipeline_instance_id IN (
  SELECT id FROM batch.pipeline_instance WHERE related_job_instance_id IN (SELECT id FROM ji)
);

WITH ji AS (
  SELECT id FROM p2_cleanup_job_instance_ids
)
DELETE FROM batch.file_dispatch_record
WHERE pipeline_instance_id IN (
  SELECT id FROM batch.pipeline_instance WHERE related_job_instance_id IN (SELECT id FROM ji)
);

WITH ji AS (
  SELECT id FROM p2_cleanup_job_instance_ids
)
DELETE FROM batch.pipeline_instance WHERE related_job_instance_id IN (SELECT id FROM ji);

WITH ji AS (
  SELECT id FROM p2_cleanup_job_instance_ids
)
DELETE FROM batch.workflow_run WHERE related_job_instance_id IN (SELECT id FROM ji);

WITH ji AS (
  SELECT id FROM p2_cleanup_job_instance_ids
)
DELETE FROM batch.job_execution_log WHERE job_instance_id IN (SELECT id FROM ji);

WITH ji AS (
  SELECT id FROM p2_cleanup_job_instance_ids
)
DELETE FROM batch.compensation_command WHERE related_job_instance_id IN (SELECT id FROM ji);

WITH ji AS (
  SELECT id FROM p2_cleanup_job_instance_ids
),
jt AS (
  SELECT id FROM batch.job_task WHERE job_instance_id IN (SELECT id FROM ji)
),
jp AS (
  SELECT id FROM batch.job_partition WHERE job_instance_id IN (SELECT id FROM ji)
)
DELETE FROM batch.dead_letter_task
WHERE (
    (source_type = 'JOB_INSTANCE' AND source_id IN (SELECT id FROM ji))
    OR (source_type = 'JOB_PARTITION' AND source_id IN (SELECT id FROM jp))
    OR (source_type = 'JOB_TASK' AND source_id IN (SELECT id FROM jt))
  );

WITH ji AS (
  SELECT id FROM p2_cleanup_job_instance_ids
),
jt AS (
  SELECT id FROM batch.job_task WHERE job_instance_id IN (SELECT id FROM ji)
),
jp AS (
  SELECT id FROM batch.job_partition WHERE job_instance_id IN (SELECT id FROM ji)
)
DELETE FROM batch.retry_schedule
WHERE (
    (related_type = 'JOB_INSTANCE' AND related_id IN (SELECT id FROM ji))
    OR (related_type = 'JOB_PARTITION' AND related_id IN (SELECT id FROM jp))
    OR (related_type = 'JOB_TASK' AND related_id IN (SELECT id FROM jt))
  );

WITH ji AS (
  SELECT id FROM p2_cleanup_job_instance_ids
)
DELETE FROM batch.job_task WHERE job_instance_id IN (SELECT id FROM ji);

WITH ji AS (
  SELECT id FROM p2_cleanup_job_instance_ids
)
DELETE FROM batch.job_partition WHERE job_instance_id IN (SELECT id FROM ji);

UPDATE batch.trigger_request
SET related_job_instance_id = NULL
WHERE related_job_instance_id IN (
    SELECT id FROM p2_cleanup_job_instance_ids
  );

DELETE FROM batch.job_instance
WHERE id IN (SELECT id FROM p2_cleanup_job_instance_ids);

DELETE FROM batch.trigger_outbox_event
WHERE request_id LIKE ('%' || :'run_id' || '%');

DELETE FROM batch.trigger_request
WHERE (
    request_id LIKE ('%' || :'run_id' || '%')
    OR dedup_key LIKE ('%' || :'run_id' || '%')
    OR trace_id LIKE ('%' || :'run_id' || '%')
  );

COMMIT;
