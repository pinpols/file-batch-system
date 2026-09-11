SELECT count(*) FROM batch.job_definition
WHERE job_type = 'ATOMIC'
  AND (default_params->>'taskType') NOT IN ('shell', 'sql', 'stored_proc', 'http')
  AND lower(coalesce(worker_group, '')) <> 'sdk-self-hosted';
