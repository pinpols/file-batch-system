SELECT coalesce(
    md5(string_agg(tenant_id || '/' || dedup_key || '/' || run_attempt, ',' ORDER BY id)),
    'EMPTY'
)
FROM batch.job_instance
WHERE created_at <= :'target_time'::timestamptz;
