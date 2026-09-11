SELECT tenant_id,
       biz_date,
       day_status,
       created_at,
       updated_at
FROM batch.batch_day_instance
WHERE updated_at >= CURRENT_TIMESTAMP - interval '36 hours'
ORDER BY biz_date, updated_at
LIMIT 50;
