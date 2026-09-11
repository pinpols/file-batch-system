WITH scoped_instances AS (
    SELECT id, instance_status
    FROM batch.job_instance
    WHERE tenant_id = :'tenant_id'
      AND params_snapshot::text LIKE '%' || :'run_id' || '%'
), scoped_trigger_requests AS (
    SELECT related_job_instance_id
    FROM batch.trigger_request
    WHERE tenant_id = :'tenant_id'
      AND (
          request_id LIKE '%' || :'run_id' || '%'
          OR dedup_key LIKE '%' || :'run_id' || '%'
          OR trace_id LIKE '%' || :'run_id' || '%'
      )
)
SELECT
    (SELECT count(*) FROM scoped_instances) || '|' ||
    (SELECT count(*) FROM scoped_instances
     WHERE instance_status IN ('SUCCESS','FAILED','PARTIAL_FAILED','CANCELLED','TERMINATED','REJECTED')) || '|' ||
    (SELECT count(*) FROM scoped_trigger_requests) || '|' ||
    (SELECT count(*)
     FROM scoped_trigger_requests request
     JOIN batch.job_instance instance
       ON instance.tenant_id = :'tenant_id'
      AND instance.id = request.related_job_instance_id
     WHERE instance.instance_status IN ('SUCCESS','FAILED','PARTIAL_FAILED','CANCELLED','TERMINATED','REJECTED'));
