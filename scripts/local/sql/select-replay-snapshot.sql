SELECT COALESCE(json_agg(t), '[]'::json)
FROM (
  SELECT DISTINCT ON (job_code, biz_date)
    instance_no, tenant_id, job_code, biz_date::TEXT,
    instance_status,
    COALESCE(success_partition_count,0) + COALESCE(failed_partition_count,0) AS processed_count,
    started_at, finished_at,
    result_summary, params_snapshot
  FROM batch.job_instance
  WHERE tenant_id = :'tenant_id'
    AND biz_date BETWEEN :'biz_from'::date AND :'biz_to'::date
  ORDER BY job_code, biz_date, id DESC
) t;
