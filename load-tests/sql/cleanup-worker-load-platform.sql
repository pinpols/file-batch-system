BEGIN;

-- run_id is generated per profile and is the cleanup boundary. Do not pin this
-- fixture cleanup to default-tenant: fairness profiles intentionally use ta/tb/tc.
-- 优先通过已关联实例反查 Trigger 请求；新 Gatling 场景还会把 run_id 写入请求/追踪标识。
-- 不扫描 trigger_outbox_event.payload：该 JSONB 全表文本匹配在十万级清理时会拖慢回收，且
-- run_id 已有结构化的 request_id / trace_id 归属边界。
CREATE TEMP TABLE cleanup_trigger_requests ON COMMIT DROP AS
SELECT DISTINCT tr.id, tr.request_id
FROM batch.trigger_request tr
WHERE tr.request_id LIKE '%' || :'run_id' || '%'
   OR tr.dedup_key LIKE '%' || :'run_id' || '%'
   OR tr.trace_id LIKE '%' || :'run_id' || '%'
UNION
SELECT DISTINCT tr.id, tr.request_id
FROM batch.trigger_request tr
JOIN batch.job_instance ji ON ji.id = tr.related_job_instance_id
WHERE ji.params_snapshot::text LIKE '%' || :'run_id' || '%'
   OR ji.trace_id LIKE :'run_id' || '%'
   OR ji.batch_no = :'run_id' || '-SETTLEMENT';

WITH ji AS (
  SELECT id
  FROM batch.job_instance
  WHERE job_code IN ('import_customer_job', 'export_settlement_job', 'lt_dispatch_local_job', 'lt_process_sql_job', 'lt_process_copy_job', 'atomic_sql_demo')
    AND (
      trace_id LIKE :'run_id' || '%'
      OR params_snapshot::text LIKE '%' || :'run_id' || '%'
      OR batch_no = :'run_id' || '-SETTLEMENT'
    )
),
tasks AS (
  SELECT id FROM batch.job_task WHERE job_instance_id IN (SELECT id FROM ji)
)
DELETE FROM batch.job_step_instance WHERE job_task_id IN (SELECT id FROM tasks);

WITH ji AS (
  SELECT id
  FROM batch.job_instance
  WHERE job_code IN ('import_customer_job', 'export_settlement_job', 'lt_dispatch_local_job', 'lt_process_sql_job', 'lt_process_copy_job', 'atomic_sql_demo')
    AND (
      trace_id LIKE :'run_id' || '%'
      OR params_snapshot::text LIKE '%' || :'run_id' || '%'
      OR batch_no = :'run_id' || '-SETTLEMENT'
    )
)
DELETE FROM batch.job_task WHERE job_instance_id IN (SELECT id FROM ji);

WITH ji AS (
  SELECT id
  FROM batch.job_instance
  WHERE job_code IN ('import_customer_job', 'export_settlement_job', 'lt_dispatch_local_job', 'lt_process_sql_job', 'lt_process_copy_job', 'atomic_sql_demo')
    AND (
      trace_id LIKE :'run_id' || '%'
      OR params_snapshot::text LIKE '%' || :'run_id' || '%'
      OR batch_no = :'run_id' || '-SETTLEMENT'
    )
)
DELETE FROM batch.job_partition WHERE job_instance_id IN (SELECT id FROM ji);

WITH ji AS (
  SELECT id
  FROM batch.job_instance
  WHERE job_code IN ('import_customer_job', 'export_settlement_job', 'lt_dispatch_local_job', 'lt_process_sql_job', 'lt_process_copy_job', 'atomic_sql_demo')
    AND (
      trace_id LIKE :'run_id' || '%'
      OR params_snapshot::text LIKE '%' || :'run_id' || '%'
      OR batch_no = :'run_id' || '-SETTLEMENT'
    )
)
DELETE FROM batch.pipeline_step_run
WHERE pipeline_instance_id IN (
  SELECT id FROM batch.pipeline_instance WHERE related_job_instance_id IN (SELECT id FROM ji)
);

WITH ji AS (
  SELECT id
  FROM batch.job_instance
  WHERE job_code IN ('import_customer_job', 'export_settlement_job', 'lt_dispatch_local_job', 'lt_process_sql_job', 'lt_process_copy_job', 'atomic_sql_demo')
    AND (
      trace_id LIKE :'run_id' || '%'
      OR params_snapshot::text LIKE '%' || :'run_id' || '%'
      OR batch_no = :'run_id' || '-SETTLEMENT'
    )
)
DELETE FROM batch.file_dispatch_record
WHERE pipeline_instance_id IN (
  SELECT id FROM batch.pipeline_instance WHERE related_job_instance_id IN (SELECT id FROM ji)
);

WITH ji AS (
  SELECT id
  FROM batch.job_instance
  WHERE job_code IN ('import_customer_job', 'export_settlement_job', 'lt_dispatch_local_job', 'lt_process_sql_job', 'lt_process_copy_job', 'atomic_sql_demo')
    AND (
      trace_id LIKE :'run_id' || '%'
      OR params_snapshot::text LIKE '%' || :'run_id' || '%'
      OR batch_no = :'run_id' || '-SETTLEMENT'
    )
)
DELETE FROM batch.pipeline_instance WHERE related_job_instance_id IN (SELECT id FROM ji);

WITH ji AS (
  SELECT id
  FROM batch.job_instance
  WHERE job_code IN ('import_customer_job', 'export_settlement_job', 'lt_dispatch_local_job', 'lt_process_sql_job', 'lt_process_copy_job', 'atomic_sql_demo')
    AND (
      trace_id LIKE :'run_id' || '%'
      OR params_snapshot::text LIKE '%' || :'run_id' || '%'
      OR batch_no = :'run_id' || '-SETTLEMENT'
    )
)
DELETE FROM batch.workflow_run WHERE related_job_instance_id IN (SELECT id FROM ji);

WITH ji AS (
  SELECT id
  FROM batch.job_instance
  WHERE job_code IN ('import_customer_job', 'export_settlement_job', 'lt_dispatch_local_job', 'lt_process_sql_job', 'lt_process_copy_job', 'atomic_sql_demo')
    AND (
      trace_id LIKE :'run_id' || '%'
      OR params_snapshot::text LIKE '%' || :'run_id' || '%'
      OR batch_no = :'run_id' || '-SETTLEMENT'
    )
)
DELETE FROM batch.job_execution_log WHERE job_instance_id IN (SELECT id FROM ji);

WITH ji AS (
  SELECT id
  FROM batch.job_instance
  WHERE job_code IN ('import_customer_job', 'export_settlement_job', 'lt_dispatch_local_job', 'lt_process_sql_job', 'lt_process_copy_job', 'atomic_sql_demo')
    AND (
      trace_id LIKE :'run_id' || '%'
      OR params_snapshot::text LIKE '%' || :'run_id' || '%'
      OR batch_no = :'run_id' || '-SETTLEMENT'
    )
)
DELETE FROM batch.compensation_command WHERE related_job_instance_id IN (SELECT id FROM ji);

WITH ji AS (
  SELECT id
  FROM batch.job_instance
  WHERE job_code IN ('import_customer_job', 'export_settlement_job', 'lt_dispatch_local_job', 'lt_process_sql_job', 'lt_process_copy_job', 'atomic_sql_demo')
    AND (
      trace_id LIKE :'run_id' || '%'
      OR params_snapshot::text LIKE '%' || :'run_id' || '%'
      OR batch_no = :'run_id' || '-SETTLEMENT'
    )
),
jp AS (
  SELECT id FROM batch.job_partition WHERE job_instance_id IN (SELECT id FROM ji)
),
jt AS (
  SELECT id FROM batch.job_task WHERE job_instance_id IN (SELECT id FROM ji)
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
  SELECT id
  FROM batch.job_instance
  WHERE job_code IN ('import_customer_job', 'export_settlement_job', 'lt_dispatch_local_job', 'lt_process_sql_job', 'lt_process_copy_job', 'atomic_sql_demo')
    AND (
      trace_id LIKE :'run_id' || '%'
      OR params_snapshot::text LIKE '%' || :'run_id' || '%'
      OR batch_no = :'run_id' || '-SETTLEMENT'
    )
),
jp AS (
  SELECT id FROM batch.job_partition WHERE job_instance_id IN (SELECT id FROM ji)
),
jt AS (
  SELECT id FROM batch.job_task WHERE job_instance_id IN (SELECT id FROM ji)
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
  SELECT id
  FROM batch.job_instance
  WHERE job_code IN ('import_customer_job', 'export_settlement_job', 'lt_dispatch_local_job', 'lt_process_sql_job', 'lt_process_copy_job', 'atomic_sql_demo')
    AND (
      trace_id LIKE :'run_id' || '%'
      OR params_snapshot::text LIKE '%' || :'run_id' || '%'
      OR batch_no = :'run_id' || '-SETTLEMENT'
    )
),
jp AS (
  SELECT id FROM batch.job_partition WHERE job_instance_id IN (SELECT id FROM ji)
),
jt AS (
  SELECT id FROM batch.job_task WHERE job_instance_id IN (SELECT id FROM ji)
)
DELETE FROM batch.outbox_event
WHERE (
    (aggregate_type = 'JOB_INSTANCE' AND aggregate_id IN (SELECT id FROM ji))
    OR (aggregate_type = 'JOB_PARTITION' AND aggregate_id IN (SELECT id FROM jp))
    OR (aggregate_type = 'JOB_TASK' AND aggregate_id IN (SELECT id FROM jt))
  );

WITH ji AS (
  SELECT id
  FROM batch.job_instance
  WHERE job_code IN ('import_customer_job', 'export_settlement_job', 'lt_dispatch_local_job', 'lt_process_sql_job', 'lt_process_copy_job', 'atomic_sql_demo')
    AND (
      trace_id LIKE :'run_id' || '%'
      OR params_snapshot::text LIKE '%' || :'run_id' || '%'
      OR batch_no = :'run_id' || '-SETTLEMENT'
    )
),
jp AS (
  SELECT id FROM batch.job_partition WHERE job_instance_id IN (SELECT id FROM ji)
),
jt AS (
  SELECT id FROM batch.job_task WHERE job_instance_id IN (SELECT id FROM ji)
)
DELETE FROM batch.dead_letter_task
WHERE (
    (source_type = 'JOB_INSTANCE' AND source_id IN (SELECT id FROM ji))
    OR (source_type = 'JOB_PARTITION' AND source_id IN (SELECT id FROM jp))
    OR (source_type = 'JOB_TASK' AND source_id IN (SELECT id FROM jt))
  );

WITH ji AS (
  SELECT id
  FROM batch.job_instance
  WHERE job_code IN ('import_customer_job', 'export_settlement_job', 'lt_dispatch_local_job', 'lt_process_sql_job', 'lt_process_copy_job', 'atomic_sql_demo')
    AND (
      trace_id LIKE :'run_id' || '%'
      OR params_snapshot::text LIKE '%' || :'run_id' || '%'
      OR batch_no = :'run_id' || '-SETTLEMENT'
    )
),
jp AS (
  SELECT id FROM batch.job_partition WHERE job_instance_id IN (SELECT id FROM ji)
),
jt AS (
  SELECT id FROM batch.job_task WHERE job_instance_id IN (SELECT id FROM ji)
)
DELETE FROM batch.retry_schedule
WHERE (
    (related_type = 'JOB_INSTANCE' AND related_id IN (SELECT id FROM ji))
    OR (related_type = 'JOB_PARTITION' AND related_id IN (SELECT id FROM jp))
    OR (related_type = 'JOB_TASK' AND related_id IN (SELECT id FROM jt))
  );

DELETE FROM batch.trigger_outbox_event
WHERE request_id IN (SELECT request_id FROM cleanup_trigger_requests);

WITH ji AS (
  SELECT id
  FROM batch.job_instance
  WHERE job_code IN ('import_customer_job', 'export_settlement_job', 'lt_dispatch_local_job', 'lt_process_sql_job', 'lt_process_copy_job', 'atomic_sql_demo')
    AND (
      trace_id LIKE :'run_id' || '%'
      OR params_snapshot::text LIKE '%' || :'run_id' || '%'
      OR batch_no = :'run_id' || '-SETTLEMENT'
    )
)
UPDATE batch.trigger_request
SET related_job_instance_id = NULL
WHERE related_job_instance_id IN (SELECT id FROM ji)
   OR id IN (SELECT id FROM cleanup_trigger_requests);

DELETE FROM batch.job_instance
WHERE (
    trigger_request_id IN (SELECT id FROM cleanup_trigger_requests)
    OR (
      job_code IN ('import_customer_job', 'export_settlement_job', 'lt_dispatch_local_job', 'lt_process_sql_job', 'lt_process_copy_job', 'atomic_sql_demo')
      AND (
      trace_id LIKE :'run_id' || '%'
      OR params_snapshot::text LIKE '%' || :'run_id' || '%'
      OR batch_no = :'run_id' || '-SETTLEMENT'
      )
    )
  );

DELETE FROM batch.trigger_request
WHERE (
    id IN (SELECT id FROM cleanup_trigger_requests)
    OR request_id LIKE '%' || :'run_id' || '%'
    OR dedup_key LIKE '%' || :'run_id' || '%'
    OR trace_id LIKE '%' || :'run_id' || '%'
  );

DELETE FROM batch.file_dispatch_record
WHERE (external_request_id LIKE '%' || :'run_id' || '%' OR file_id IN (
    SELECT id FROM batch.file_record WHERE metadata_json::text LIKE '%' || :'run_id' || '%'
  ));

DELETE FROM batch.file_audit_log
WHERE file_id IN (
    SELECT id FROM batch.file_record
    WHERE (file_code LIKE :'run_id' || '%' OR metadata_json::text LIKE '%' || :'run_id' || '%')
  );

DELETE FROM batch.file_record
WHERE (file_code LIKE :'run_id' || '%' OR metadata_json::text LIKE '%' || :'run_id' || '%');

COMMIT;
