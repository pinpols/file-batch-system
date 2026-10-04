WITH selected_modules AS (
    SELECT trim(value) AS module
    FROM regexp_split_to_table(:'modules_csv', ',') AS value
),
selected_jobs AS (
    SELECT 'lt_process_sql_job' AS job_code
    WHERE EXISTS (SELECT 1 FROM selected_modules WHERE module = 'process')
    UNION ALL
    SELECT 'lt_dispatch_local_job'
    WHERE EXISTS (SELECT 1 FROM selected_modules WHERE module = 'dispatch')
    UNION ALL
    SELECT trim(value)
    FROM regexp_split_to_table(:'atomic_jobs_csv', ',') AS value
    WHERE EXISTS (SELECT 1 FROM selected_modules WHERE module = 'atomic')
)
SELECT COALESCE(MIN(NULLIF(job.timeout_seconds, 0)), 0)
FROM batch.job_definition job
JOIN selected_jobs selected ON selected.job_code = job.job_code
WHERE job.tenant_id = :'tenant_id';
