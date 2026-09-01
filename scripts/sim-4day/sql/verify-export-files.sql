WITH export_runs AS (
    SELECT DISTINCT i.tenant_id, i.biz_date, i.job_code
    FROM batch.job_instance i
    JOIN batch.trigger_request r ON r.id = i.trigger_request_id
    WHERE r.request_id LIKE :'run_prefix'
      AND i.job_code IN ('TA_EXPORT_REPORT', 'TB_EXPORT_STATEMENT', 'TC_EXPORT_RISK_ALERT')
), matched AS (
    SELECT e.*,
           EXISTS (
               SELECT 1
               FROM batch.file_record f
               WHERE f.tenant_id = e.tenant_id
                 AND f.biz_date = e.biz_date
          AND f.file_status IN ('GENERATED', 'DISPATCHED')
                 AND f.file_size_bytes > 0
                 AND f.storage_path LIKE 'outbound/' || e.job_code || '/%'
           ) AS has_non_empty_file
    FROM export_runs e
)
SELECT count(*) AS export_total,
       count(*) FILTER (WHERE NOT has_non_empty_file) AS export_invalid
FROM matched;
