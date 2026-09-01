-- Stage 6d fallback fixture: create one MANUAL_APPROVAL misfire pending row
-- when local Quartz fault injection cannot deterministically surface a
-- triggerMisfired callback. Required psql variables: batch_no, biz_date.

WITH state_row AS (
    INSERT INTO batch.trigger_runtime_state (
        job_definition_id, tenant_id, job_code, next_fire_time,
        last_fire_status, scheduled_at, version, created_at, updated_at
    )
    SELECT job.id,
           job.tenant_id,
           job.job_code,
           :'biz_date'::date + TIME '03:00:00',
           'MISFIRE_PENDING',
           CURRENT_TIMESTAMP,
           0,
           CURRENT_TIMESTAMP,
           CURRENT_TIMESTAMP
    FROM batch.job_definition job
    WHERE job.tenant_id = 'ta'
      AND job.job_code = 'TA_TRIGGER_STAGE6C_MISFIRE'
    ON CONFLICT (job_definition_id) DO UPDATE
    SET tenant_id = EXCLUDED.tenant_id,
        job_code = EXCLUDED.job_code,
        next_fire_time = EXCLUDED.next_fire_time,
        last_fire_status = EXCLUDED.last_fire_status,
        scheduled_at = EXCLUDED.scheduled_at,
        updated_at = CURRENT_TIMESTAMP
    RETURNING id
),
request_row AS (
    INSERT INTO batch.trigger_request (
        tenant_id, request_id, trigger_type, job_code, biz_date, dedup_key,
        request_status, trace_id, dry_run
    )
    VALUES (
        'ta',
        :'batch_no' || '-misfire-fallback',
        'CATCH_UP',
        'TA_TRIGGER_STAGE6C_MISFIRE',
        :'biz_date'::date,
        :'batch_no' || '-misfire-fallback',
        'ACCEPTED',
        :'batch_no' || '-misfire-fallback-trace',
        false
    )
    ON CONFLICT (tenant_id, request_id) DO UPDATE
    SET trigger_type = EXCLUDED.trigger_type,
        job_code = EXCLUDED.job_code,
        biz_date = EXCLUDED.biz_date,
        dedup_key = EXCLUDED.dedup_key,
        request_status = EXCLUDED.request_status,
        trace_id = EXCLUDED.trace_id,
        related_job_instance_id = NULL,
        updated_at = CURRENT_TIMESTAMP
    RETURNING id
)
INSERT INTO batch.trigger_misfire_pending (
    trigger_runtime_state_id, tenant_id, job_code, scheduled_fire_time,
    detected_at, status, catch_up_request_id, expires_at, created_at, updated_at
)
SELECT state_row.id,
       'ta',
       'TA_TRIGGER_STAGE6C_MISFIRE',
       :'biz_date'::date + TIME '03:00:00',
       CURRENT_TIMESTAMP,
       'PENDING',
       request_row.id,
       CURRENT_TIMESTAMP + INTERVAL '7 days',
       CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP
FROM state_row CROSS JOIN request_row
ON CONFLICT (trigger_runtime_state_id, scheduled_fire_time) DO UPDATE
SET status = 'PENDING',
    catch_up_request_id = EXCLUDED.catch_up_request_id,
    approved_by = NULL,
    approved_at = NULL,
    rejection_reason = NULL,
    expires_at = EXCLUDED.expires_at,
    updated_at = CURRENT_TIMESTAMP;
