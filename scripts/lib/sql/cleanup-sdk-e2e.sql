BEGIN;

UPDATE batch.job_instance
SET instance_status = 'CANCELLED'
WHERE tenant_id = :'tenant_id'
  AND job_code = :'job_code'
  AND instance_status = 'RUNNING';

DELETE FROM batch.job_task
WHERE tenant_id = :'tenant_id'
  AND job_instance_id IN (
      SELECT id
      FROM batch.job_instance
      WHERE tenant_id = :'tenant_id'
        AND job_code = :'job_code'
  );

DELETE FROM batch.job_partition
WHERE tenant_id = :'tenant_id'
  AND job_instance_id IN (
      SELECT id
      FROM batch.job_instance
      WHERE tenant_id = :'tenant_id'
        AND job_code = :'job_code'
  );

DELETE FROM batch.job_instance
WHERE tenant_id = :'tenant_id'
  AND job_code = :'job_code';

DELETE FROM batch.job_definition
WHERE tenant_id = :'tenant_id'
  AND job_code = :'job_code';

DELETE FROM batch.resource_queue
WHERE tenant_id = :'tenant_id'
  AND queue_code = :'queue_code'
  AND NOT EXISTS (
      SELECT 1
      FROM batch.job_definition
      WHERE tenant_id = :'tenant_id'
        AND queue_code = :'queue_code'
  );

DELETE FROM batch.worker_registry
WHERE tenant_id = :'tenant_id'
  AND worker_code = :'worker_code';

DELETE FROM batch.api_key
WHERE tenant_id = :'tenant_id'
  AND key_name = :'worker_code';

DELETE FROM batch.trigger_outbox_event event
WHERE event.tenant_id = :'tenant_id'
  AND EXISTS (
      SELECT 1
      FROM batch.trigger_request request
      WHERE request.tenant_id = event.tenant_id
        AND request.request_id = event.request_id
        AND request.job_code = :'job_code'
  );

DELETE FROM batch.trigger_request
WHERE tenant_id = :'tenant_id'
  AND job_code = :'job_code';

COMMIT;
