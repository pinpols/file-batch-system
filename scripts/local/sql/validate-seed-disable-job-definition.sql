UPDATE batch.job_definition
SET enabled = FALSE,
    updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = :'tenant_id'
  AND id = :'job_definition_id'::bigint;
