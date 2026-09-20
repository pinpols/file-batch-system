BEGIN;

DELETE FROM batch.worker_registry
WHERE tenant_id = :'tenant_id'
  AND worker_code = :'worker_code';

DELETE FROM batch.api_key
WHERE tenant_id = :'tenant_id'
  AND key_name = :'worker_code';

COMMIT;
