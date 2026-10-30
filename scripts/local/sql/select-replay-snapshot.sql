SELECT COALESCE(json_agg(t), '[]'::json)
FROM (
  SELECT DISTINCT ON (job_instance.job_code, job_instance.biz_date)
    job_instance.instance_no, job_instance.tenant_id, job_instance.job_code,
    job_instance.biz_date::TEXT, job_instance.instance_status,
    COALESCE(job_instance.success_partition_count,0)
      + COALESCE(job_instance.failed_partition_count,0) AS processed_count,
    job_instance.started_at, job_instance.finished_at,
    job_instance.result_summary, job_instance.params_snapshot
  FROM :"replay_schema".forensic_replay_requests replay_request
  JOIN batch.trigger_request request
    ON request.tenant_id = replay_request.tenant_id
   AND request.request_id = replay_request.request_id
  JOIN batch.workflow_run workflow_run
    ON workflow_run.tenant_id = request.tenant_id
   AND workflow_run.trigger_request_id = request.id
  JOIN batch.workflow_node_run node_run
    ON node_run.workflow_run_id = workflow_run.id
   AND node_run.job_instance_id IS NOT NULL
  JOIN batch.job_instance job_instance
    ON job_instance.tenant_id = workflow_run.tenant_id
   AND job_instance.id = node_run.job_instance_id
  WHERE request.tenant_id = :'tenant_id'
    AND job_instance.biz_date BETWEEN :'biz_from'::date AND :'biz_to'::date
  ORDER BY job_instance.job_code, job_instance.biz_date, job_instance.id DESC
) t;
