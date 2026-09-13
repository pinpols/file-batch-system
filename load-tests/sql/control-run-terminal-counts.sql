SELECT count(instance.id) || '|'
       || count(instance.id) FILTER (
           WHERE instance.instance_status IN (
               'SUCCESS', 'FAILED', 'PARTIAL_FAILED', 'CANCELLED', 'TERMINATED', 'REJECTED'
           )
       ) || '|'
       || count(*) || '|'
       || count(*) FILTER (
           WHERE request.related_job_instance_id = instance.id
             AND instance.instance_status IN (
                 'SUCCESS', 'FAILED', 'PARTIAL_FAILED', 'CANCELLED', 'TERMINATED', 'REJECTED'
             )
       )
FROM batch.trigger_request request
LEFT JOIN batch.job_instance instance
  ON instance.tenant_id = request.tenant_id
 AND instance.trigger_request_id = request.id
 AND instance.biz_date >= :'biz_date'::date
 AND instance.biz_date < :'biz_date'::date + :'biz_date_cardinality'::integer
WHERE request.tenant_id = :'tenant_id'
  AND request.request_id LIKE :'run_id' || '-%';
