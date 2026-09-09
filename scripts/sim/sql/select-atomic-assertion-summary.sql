SELECT (
           SELECT instance.instance_status
           FROM batch.trigger_request request
           JOIN batch.job_instance instance ON instance.id = request.related_job_instance_id
           WHERE request.tenant_id = :'tenant_id'
             AND request.request_id = :'http_request_id'
           ORDER BY request.created_at DESC
           LIMIT 1
       ),
       (
           SELECT instance.instance_status || ':' || coalesce(task.error_code, '')
               || ':' || coalesce(task.failure_class, '')
           FROM batch.trigger_request request
           JOIN batch.job_instance instance ON instance.id = request.related_job_instance_id
           JOIN batch.job_task task ON task.job_instance_id = instance.id
           WHERE request.tenant_id = :'tenant_id'
             AND request.request_id = :'timeout_request_id'
           ORDER BY task.id DESC
           LIMIT 1
       ),
       (
           SELECT instance.instance_status || ':' || task.task_status
               || ':' || task.cancel_requested::text
           FROM batch.trigger_request request
           JOIN batch.job_instance instance ON instance.id = request.related_job_instance_id
           JOIN batch.job_task task ON task.job_instance_id = instance.id
           WHERE request.tenant_id = :'tenant_id'
             AND request.request_id = :'cancel_request_id'
           ORDER BY task.id DESC
           LIMIT 1
       );
