SELECT count(*) FROM batch.job_definition
WHERE job_type = 'ATOMIC' AND (default_params->>'taskType') IS NULL
  AND lower(coalesce(worker_group, '')) <> 'sdk-self-hosted';
