SELECT instance.job_code,
       coalesce(requested.run_status, instance.instance_status, requested.request_status),
       t.task_status,
       t.error_code,
       left(coalesce(t.error_message, ''), 120) AS error_message
FROM (
    SELECT tr.id, tr.tenant_id, tr.request_id, tr.created_at, tr.request_status,
           wr.run_status, node_run.job_instance_id
    FROM batch.trigger_request tr
    LEFT JOIN batch.workflow_run wr
      ON wr.tenant_id = tr.tenant_id
     AND wr.trigger_request_id = tr.id
    LEFT JOIN batch.workflow_node_run node_run
      ON node_run.workflow_run_id = wr.id
     AND node_run.job_instance_id IS NOT NULL
    WHERE tr.tenant_id = :'tenant_id'
      AND tr.request_id = ANY (string_to_array(:'request_ids_csv', ','))
) requested
LEFT JOIN batch.job_instance instance
  ON instance.tenant_id = requested.tenant_id
 AND instance.id = requested.job_instance_id
LEFT JOIN batch.job_task t ON t.job_instance_id = instance.id
ORDER BY requested.created_at, instance.job_code;
