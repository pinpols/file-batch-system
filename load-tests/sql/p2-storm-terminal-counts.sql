WITH scoped_trigger_requests AS (
    SELECT id, related_job_instance_id
    FROM batch.trigger_request
    WHERE tenant_id = :'capacity_tenant_id'
      AND request_id LIKE :'storm_run_id' || '-%'
), scoped_instances AS (
    SELECT instance.id, instance.instance_status
    FROM scoped_trigger_requests request
    JOIN batch.job_instance instance
      ON instance.tenant_id = :'capacity_tenant_id'
     AND instance.trigger_request_id = request.id
     AND instance.biz_date >= :'biz_date'::date
     AND instance.biz_date < :'biz_date'::date + :'biz_date_cardinality'::integer
)
SELECT (SELECT count(*) FROM scoped_instances) || '|'
       || (SELECT count(*)
           FROM scoped_instances
           WHERE instance_status IN (
               'SUCCESS', 'FAILED', 'PARTIAL_FAILED', 'CANCELLED', 'TERMINATED', 'REJECTED'
           )) || '|'
       || (SELECT count(*) FROM scoped_trigger_requests) || '|'
       || (SELECT count(*)
           FROM scoped_trigger_requests request
           JOIN batch.job_instance instance
             ON instance.tenant_id = :'capacity_tenant_id'
            AND instance.id = request.related_job_instance_id
            AND instance.biz_date >= :'biz_date'::date
            AND instance.biz_date < :'biz_date'::date + :'biz_date_cardinality'::integer
           WHERE instance.instance_status IN (
               'SUCCESS', 'FAILED', 'PARTIAL_FAILED', 'CANCELLED', 'TERMINATED', 'REJECTED'
           ));
