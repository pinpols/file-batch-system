SELECT count(*)
FROM (
    SELECT tenant_id, dedup_key, run_attempt
    FROM batch.job_instance
    GROUP BY tenant_id, dedup_key, run_attempt
    HAVING count(*) > 1
) duplicates;
