DELETE FROM batch.process_staging staging
USING batch.process_staging_generation generation
WHERE staging.tenant_id = generation.tenant_id
  AND staging.staging_generation_id = generation.generation_id
  AND staging.batch_key = generation.batch_key
  AND generation.tenant_id = :'tenant_id'
  AND generation.batch_key = :'batch_key'
  AND generation.job_task_id IS NULL
  AND generation.generation_status = 'OPEN'
  AND staging.payload->>'account_id' = 'A999';

DELETE FROM batch.process_staging_generation
WHERE tenant_id = :'tenant_id'
  AND batch_key = :'batch_key'
  AND job_task_id IS NULL
  AND generation_status = 'OPEN';
