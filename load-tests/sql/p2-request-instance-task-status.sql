SELECT
  i.id || '|' ||
  coalesce(i.instance_status, '') || '|' ||
  coalesce(p.id::text, '') || '|' ||
  coalesce(p.partition_status, '') || '|' ||
  coalesce(t.id::text, '') || '|' ||
  coalesce(t.task_status, '') || '|' ||
  coalesce(t.error_code, '')
FROM batch.trigger_request tr
JOIN batch.job_instance i ON i.id = tr.related_job_instance_id
LEFT JOIN batch.job_partition p ON p.job_instance_id = i.id
LEFT JOIN batch.job_task t ON t.job_partition_id = p.id
WHERE tr.tenant_id = :'tenant_id'
  AND tr.request_id = :'request_id'
ORDER BY t.id DESC NULLS LAST
LIMIT 1;
