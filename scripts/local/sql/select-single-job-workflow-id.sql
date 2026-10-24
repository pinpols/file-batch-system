SELECT workflow.id
FROM batch.workflow_definition workflow
JOIN batch.job_definition job
  ON job.tenant_id = workflow.tenant_id
 AND job.id = workflow.single_job_definition_id
WHERE workflow.tenant_id = :'tenant_id'
  AND job.job_code = :'job_code'
  AND workflow.enabled = TRUE
ORDER BY workflow.version DESC
LIMIT 1;
