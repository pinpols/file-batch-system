SELECT
  (SELECT count(*) FROM batch.job_instance WHERE tenant_id = :'capacity_tenant_id') || '|' ||
  (SELECT count(*) FROM batch.trigger_request WHERE tenant_id = :'capacity_tenant_id') || '|' ||
  (SELECT count(*) FROM batch.outbox_event WHERE tenant_id = :'capacity_tenant_id') || '|' ||
  (SELECT count(*) FROM batch.result_version WHERE tenant_id = :'capacity_tenant_id') || '|' ||
  (SELECT count(*) FROM batch.job_instance_dedup_key WHERE tenant_id = :'capacity_tenant_id') || '|' ||
  (SELECT count(*) FROM batch.outbox_event_dedup_key WHERE tenant_id = :'capacity_tenant_id');
