BEGIN;

WITH ji AS (
  SELECT id
  FROM batch.job_instance
  WHERE tenant_id = 'default-tenant'
    AND params_snapshot::text LIKE ('%' || :'run_id' || '%')
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
  WHERE tenant_id = 'default-tenant'
    AND (
      (aggregate_type = 'JOB_INSTANCE' AND aggregate_id IN (SELECT id FROM ji))
      OR (aggregate_type = 'JOB_PARTITION' AND aggregate_id IN (SELECT id FROM jp))
      OR (aggregate_type = 'JOB_TASK' AND aggregate_id IN (SELECT id FROM jt))
    )
)
DELETE FROM batch.event_outbox_retry
WHERE tenant_id = 'default-tenant'
  AND outbox_event_id IN (SELECT id FROM oe);

WITH ji AS (
  SELECT id FROM batch.job_instance
  WHERE tenant_id = 'default-tenant' AND params_snapshot::text LIKE ('%' || :'run_id' || '%')
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
  WHERE tenant_id = 'default-tenant'
    AND (
      (aggregate_type = 'JOB_INSTANCE' AND aggregate_id IN (SELECT id FROM ji))
      OR (aggregate_type = 'JOB_PARTITION' AND aggregate_id IN (SELECT id FROM jp))
      OR (aggregate_type = 'JOB_TASK' AND aggregate_id IN (SELECT id FROM jt))
    )
)
DELETE FROM batch.event_delivery_log
WHERE tenant_id = 'default-tenant'
  AND outbox_event_id IN (SELECT id FROM oe);

WITH ji AS (
  SELECT id FROM batch.job_instance
  WHERE tenant_id = 'default-tenant' AND params_snapshot::text LIKE ('%' || :'run_id' || '%')
),
jt AS (
  SELECT id FROM batch.job_task WHERE job_instance_id IN (SELECT id FROM ji)
),
jp AS (
  SELECT id FROM batch.job_partition WHERE job_instance_id IN (SELECT id FROM ji)
)
DELETE FROM batch.outbox_event
WHERE tenant_id = 'default-tenant'
  AND (
    (aggregate_type = 'JOB_INSTANCE' AND aggregate_id IN (SELECT id FROM ji))
    OR (aggregate_type = 'JOB_PARTITION' AND aggregate_id IN (SELECT id FROM jp))
    OR (aggregate_type = 'JOB_TASK' AND aggregate_id IN (SELECT id FROM jt))
  );

WITH ji AS (
  SELECT id FROM batch.job_instance
  WHERE tenant_id = 'default-tenant' AND params_snapshot::text LIKE ('%' || :'run_id' || '%')
),
jt AS (
  SELECT id FROM batch.job_task WHERE job_instance_id IN (SELECT id FROM ji)
)
DELETE FROM batch.job_step_instance WHERE job_task_id IN (SELECT id FROM jt);

WITH ji AS (
  SELECT id FROM batch.job_instance
  WHERE tenant_id = 'default-tenant' AND params_snapshot::text LIKE ('%' || :'run_id' || '%')
)
DELETE FROM batch.pipeline_step_run
WHERE pipeline_instance_id IN (
  SELECT id FROM batch.pipeline_instance WHERE related_job_instance_id IN (SELECT id FROM ji)
);

WITH ji AS (
  SELECT id FROM batch.job_instance
  WHERE tenant_id = 'default-tenant' AND params_snapshot::text LIKE ('%' || :'run_id' || '%')
)
DELETE FROM batch.file_dispatch_record
WHERE pipeline_instance_id IN (
  SELECT id FROM batch.pipeline_instance WHERE related_job_instance_id IN (SELECT id FROM ji)
);

WITH ji AS (
  SELECT id FROM batch.job_instance
  WHERE tenant_id = 'default-tenant' AND params_snapshot::text LIKE ('%' || :'run_id' || '%')
)
DELETE FROM batch.pipeline_instance WHERE related_job_instance_id IN (SELECT id FROM ji);

WITH ji AS (
  SELECT id FROM batch.job_instance
  WHERE tenant_id = 'default-tenant' AND params_snapshot::text LIKE ('%' || :'run_id' || '%')
)
DELETE FROM batch.workflow_run WHERE related_job_instance_id IN (SELECT id FROM ji);

WITH ji AS (
  SELECT id FROM batch.job_instance
  WHERE tenant_id = 'default-tenant' AND params_snapshot::text LIKE ('%' || :'run_id' || '%')
)
DELETE FROM batch.job_execution_log WHERE job_instance_id IN (SELECT id FROM ji);

WITH ji AS (
  SELECT id FROM batch.job_instance
  WHERE tenant_id = 'default-tenant' AND params_snapshot::text LIKE ('%' || :'run_id' || '%')
)
DELETE FROM batch.compensation_command WHERE related_job_instance_id IN (SELECT id FROM ji);

WITH ji AS (
  SELECT id FROM batch.job_instance
  WHERE tenant_id = 'default-tenant' AND params_snapshot::text LIKE ('%' || :'run_id' || '%')
),
jt AS (
  SELECT id FROM batch.job_task WHERE job_instance_id IN (SELECT id FROM ji)
),
jp AS (
  SELECT id FROM batch.job_partition WHERE job_instance_id IN (SELECT id FROM ji)
)
DELETE FROM batch.dead_letter_task
WHERE tenant_id = 'default-tenant'
  AND (
    (source_type = 'JOB_INSTANCE' AND source_id IN (SELECT id FROM ji))
    OR (source_type = 'JOB_PARTITION' AND source_id IN (SELECT id FROM jp))
    OR (source_type = 'JOB_TASK' AND source_id IN (SELECT id FROM jt))
  );

WITH ji AS (
  SELECT id FROM batch.job_instance
  WHERE tenant_id = 'default-tenant' AND params_snapshot::text LIKE ('%' || :'run_id' || '%')
),
jt AS (
  SELECT id FROM batch.job_task WHERE job_instance_id IN (SELECT id FROM ji)
),
jp AS (
  SELECT id FROM batch.job_partition WHERE job_instance_id IN (SELECT id FROM ji)
)
DELETE FROM batch.retry_schedule
WHERE tenant_id = 'default-tenant'
  AND (
    (related_type = 'JOB_INSTANCE' AND related_id IN (SELECT id FROM ji))
    OR (related_type = 'JOB_PARTITION' AND related_id IN (SELECT id FROM jp))
    OR (related_type = 'JOB_TASK' AND related_id IN (SELECT id FROM jt))
  );

WITH ji AS (
  SELECT id FROM batch.job_instance
  WHERE tenant_id = 'default-tenant' AND params_snapshot::text LIKE ('%' || :'run_id' || '%')
)
DELETE FROM batch.job_task WHERE job_instance_id IN (SELECT id FROM ji);

WITH ji AS (
  SELECT id FROM batch.job_instance
  WHERE tenant_id = 'default-tenant' AND params_snapshot::text LIKE ('%' || :'run_id' || '%')
)
DELETE FROM batch.job_partition WHERE job_instance_id IN (SELECT id FROM ji);

UPDATE batch.trigger_request
SET related_job_instance_id = NULL
WHERE tenant_id = 'default-tenant'
  AND related_job_instance_id IN (
    SELECT id FROM batch.job_instance
    WHERE tenant_id = 'default-tenant' AND params_snapshot::text LIKE ('%' || :'run_id' || '%')
  );

DELETE FROM batch.job_instance
WHERE tenant_id = 'default-tenant'
  AND params_snapshot::text LIKE ('%' || :'run_id' || '%');

DELETE FROM batch.trigger_outbox_event
WHERE tenant_id = 'default-tenant'
  AND request_id LIKE ('%' || :'run_id' || '%');

DELETE FROM batch.trigger_request
WHERE tenant_id = 'default-tenant'
  AND (
    request_id LIKE ('%' || :'run_id' || '%')
    OR dedup_key LIKE ('%' || :'run_id' || '%')
    OR trace_id LIKE ('%' || :'run_id' || '%')
  );

COMMIT;
