SELECT count(instance.id) || '|'
       || count(instance.id) FILTER (
           WHERE instance.instance_status IN (
               'SUCCESS', 'FAILED', 'PARTIAL_FAILED', 'CANCELLED', 'TERMINATED', 'REJECTED'
           )
       ) || '|'
       || count(instance.id) FILTER (WHERE instance.instance_status = 'SUCCESS')
FROM batch.trigger_request request
JOIN batch.job_instance instance
  ON instance.tenant_id = request.tenant_id
 AND instance.trigger_request_id = request.id
WHERE request.tenant_id = :'tenant_id'
  AND request.request_id LIKE :'run_id' || '-%';
