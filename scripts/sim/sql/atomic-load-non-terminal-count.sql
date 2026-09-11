SELECT count(*)
FROM batch.trigger_request tr
LEFT JOIN batch.job_instance i ON i.id = tr.related_job_instance_id
WHERE tr.tenant_id = :'tenant_id'
  AND tr.request_id = ANY (string_to_array(:'request_ids_csv', ','))
  AND coalesce(i.instance_status, '') NOT IN (
      'SUCCESS', 'FAILED', 'PARTIAL_FAILED', 'REJECTED', 'CANCELLED'
  );
