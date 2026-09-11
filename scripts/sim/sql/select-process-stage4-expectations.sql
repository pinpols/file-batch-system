WITH actual AS (
    SELECT job_code, instance_status
    FROM batch.job_instance
    WHERE tenant_id = :'tenant_id'
      AND job_code = ANY(string_to_array(:'job_codes', ','))
      AND created_at >= :'start_ts'::timestamptz
),
expected(job_code, expected_status) AS (
    VALUES ('TA_PROCESS_STAGE4_JSONB', 'SUCCESS'),
           ('TA_PROCESS_STAGE4_DIRECT', 'SUCCESS'),
           ('TA_PROCESS_STAGE4_VALIDATE_FAIL', 'FAILED'),
           ('TA_PROCESS_STAGE4_EMPTY_SUCCESS', 'SUCCESS')
)
SELECT expected.job_code,
       expected.expected_status,
       coalesce(actual.instance_status, 'MISSING') AS actual_status,
       coalesce(actual.instance_status, 'MISSING') = expected.expected_status AS ok
FROM expected
LEFT JOIN actual USING (job_code)
ORDER BY expected.job_code;
