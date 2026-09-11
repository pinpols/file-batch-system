WITH actual AS (
    SELECT params_snapshot#>>'{effectiveParams,templateCode}' AS template_code,
           instance_status
    FROM batch.job_instance
    WHERE tenant_id = :'tenant_id'
      AND job_code = 'TA_EXPORT_REPORT'
      AND created_at >= :'start_ts'::timestamptz
),
expected(template_code, expected_status) AS (
    VALUES ('TA_EXPORT_REPORT_JSON_TPL', 'SUCCESS'),
           ('TA_EXPORT_REPORT_FIXED_TPL', 'SUCCESS'),
           ('TA_EXPORT_REPORT_EXCEL_TPL', 'SUCCESS'),
           ('TA_EXPORT_REPORT_BAD_SQL_TPL', 'FAILED')
)
SELECT count(*)
FROM expected
LEFT JOIN actual USING (template_code)
WHERE coalesce(actual.instance_status, 'MISSING') <> expected.expected_status;
