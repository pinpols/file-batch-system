SELECT count(*) FROM batch.job_instance
WHERE tenant_id = :'tenant_id' AND job_code = :'job_code'
  AND coalesce(params_snapshot #>> '{requestParams,metadata,runId}', params_snapshot #>> '{effectiveParams,metadata,runId}', params_snapshot #>> '{metadata,runId}', params_snapshot #>> '{runId}') = :'run_id';
