INSERT INTO batch.job_definition (
    tenant_id,
    job_code,
    job_name,
    job_type,
    biz_type,
    schedule_type,
    schedule_expr,
    timezone,
    trigger_mode,
    enabled,
    created_by
)
VALUES (
    :'tenant_id',
    :'job_code',
    'seedval cron probe',
    'GENERAL',
    'TEST',
    'CRON',
    '0 * * * * ?',
    'Asia/Shanghai',
    'SCHEDULED',
    TRUE,
    'seedval'
)
ON CONFLICT (tenant_id, job_code) DO UPDATE
SET job_name = EXCLUDED.job_name,
    job_type = EXCLUDED.job_type,
    biz_type = EXCLUDED.biz_type,
    schedule_type = EXCLUDED.schedule_type,
    schedule_expr = EXCLUDED.schedule_expr,
    timezone = EXCLUDED.timezone,
    trigger_mode = EXCLUDED.trigger_mode,
    enabled = TRUE,
    updated_at = CURRENT_TIMESTAMP
RETURNING id;
