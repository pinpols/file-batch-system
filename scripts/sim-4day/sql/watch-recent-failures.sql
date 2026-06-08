SELECT tenant_id || ' ' || coalesce(error_code,'?') || ' ' || left(coalesce(error_message,''),60)
FROM batch.pipeline_step_run
WHERE step_status = 'FAILED'
ORDER BY id DESC
LIMIT 5;
