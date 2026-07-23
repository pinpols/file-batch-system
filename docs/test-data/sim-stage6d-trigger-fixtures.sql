-- Stage 6d trigger 压力 fixtures。
-- 必需的 psql 变量:batch_no, biz_date
--
-- 本 fixture 假定 sim-stage6c-trigger-fixtures.sql 已经创建了
-- TA_TRIGGER_STAGE6C_* 调度作业与 pipeline。Stage6d 收紧 cron 节奏并重置
-- Quartz 运行态,以便本地做确定性验证。

DELETE FROM batch.trigger_misfire_pending
WHERE tenant_id = 'ta'
  AND job_code IN ('TA_TRIGGER_STAGE6C_SCHEDULED', 'TA_TRIGGER_STAGE6C_MISFIRE');

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
    updated_at = CURRENT_TIMESTAMP;
