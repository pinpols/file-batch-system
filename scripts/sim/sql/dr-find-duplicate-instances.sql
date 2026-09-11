SELECT coalesce(string_agg(tenant_id || '/' || dedup_key || ':' || duplicate_count, ', '), '')
FROM (
    SELECT tenant_id, dedup_key, count(*) AS duplicate_count
    FROM batch.job_instance
    GROUP BY tenant_id, dedup_key, run_attempt
    HAVING count(*) > 1
) duplicates;
