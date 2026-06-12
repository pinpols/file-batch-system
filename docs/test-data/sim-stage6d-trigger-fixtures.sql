-- Stage 6d trigger pressure fixtures.
-- Required psql variables: batch_no, biz_date
--
-- This fixture assumes sim-stage6c-trigger-fixtures.sql has already created the
-- TA_TRIGGER_STAGE6C_* scheduled jobs and pipelines. Stage6d tightens the cron
-- cadence and resets runtime state for deterministic local verification.

WITH targets AS (
  SELECT id
  FROM batch.job_definition
  WHERE tenant_id = 'ta'
    AND job_code IN ('TA_TRIGGER_STAGE6C_SCHEDULED', 'TA_TRIGGER_STAGE6C_MISFIRE')
),
old_states AS (
  SELECT id
  FROM batch.trigger_runtime_state
  WHERE job_definition_id IN (SELECT id FROM targets)
)
DELETE FROM batch.trigger_misfire_pending p
USING old_states s
WHERE p.trigger_runtime_state_id = s.id;

DELETE FROM batch.trigger_runtime_state
WHERE job_definition_id IN (
  SELECT id
  FROM batch.job_definition
  WHERE tenant_id = 'ta'
    AND job_code IN ('TA_TRIGGER_STAGE6C_SCHEDULED', 'TA_TRIGGER_STAGE6C_MISFIRE')
);

DELETE FROM batch.trigger_request
WHERE tenant_id = 'ta'
  AND request_id LIKE :'batch_no' || '%';

DELETE FROM batch.trigger_outbox_event
WHERE tenant_id = 'ta'
  AND request_id LIKE :'batch_no' || '%';

UPDATE batch.job_definition
SET schedule_expr = '0/2 * * * * ?',
    enabled = true,
    description = 'Stage6d high-frequency scheduled trigger process',
    updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'ta'
  AND job_code = 'TA_TRIGGER_STAGE6C_SCHEDULED';

UPDATE batch.job_definition
SET enabled = true,
    updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'ta'
  AND job_code = 'TA_TRIGGER_STAGE6C_MISFIRE';

WITH clock AS (
  SELECT now() AS ts
),
fires AS (
  SELECT
    date_trunc('second', ts) + interval '2 seconds' AS scheduled_fire,
    date_trunc('minute', ts) - interval '120 seconds' AS misfire
  FROM clock
),
defs AS (
  SELECT id, tenant_id, job_code, timezone
  FROM batch.job_definition
  WHERE tenant_id = 'ta'
    AND job_code IN ('TA_TRIGGER_STAGE6C_SCHEDULED', 'TA_TRIGGER_STAGE6C_MISFIRE')
)
INSERT INTO batch.trigger_runtime_state (
    job_definition_id, tenant_id, job_code, next_fire_time,
    misfire_count, version, schedule_timezone,
    scheduled_local_date, scheduled_local_time, fire_sequence
)
SELECT defs.id,
       defs.tenant_id,
       defs.job_code,
       CASE WHEN defs.job_code = 'TA_TRIGGER_STAGE6C_MISFIRE' THEN fires.misfire ELSE fires.scheduled_fire END,
       0,
       1,
       defs.timezone,
       (CASE WHEN defs.job_code = 'TA_TRIGGER_STAGE6C_MISFIRE' THEN fires.misfire ELSE fires.scheduled_fire END AT TIME ZONE defs.timezone)::date,
       (CASE WHEN defs.job_code = 'TA_TRIGGER_STAGE6C_MISFIRE' THEN fires.misfire ELSE fires.scheduled_fire END AT TIME ZONE defs.timezone)::time,
       1
FROM defs CROSS JOIN fires;

INSERT INTO batch.trigger_request (
    tenant_id, request_id, trigger_type, job_code, biz_date, dedup_key,
    request_status, trace_id, dry_run
)
VALUES (
    'ta',
    :'batch_no' || '-replay',
    'CATCH_UP',
    'TA_TRIGGER_STAGE6C_SCHEDULED',
    :'biz_date'::date,
    :'batch_no' || '-replay',
    'ACCEPTED',
    :'batch_no' || '-replay-trace',
    false
)
ON CONFLICT (tenant_id, request_id) DO UPDATE
SET trigger_type = EXCLUDED.trigger_type,
    job_code = EXCLUDED.job_code,
    biz_date = EXCLUDED.biz_date,
    dedup_key = EXCLUDED.dedup_key,
    request_status = EXCLUDED.request_status,
    trace_id = EXCLUDED.trace_id,
    related_job_instance_id = null,
    updated_at = EXCLUDED.updated_at;
