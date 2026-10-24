SELECT (
           SELECT instance.instance_status || ':' || coalesce(task.error_code, '')
               || ':' || coalesce(task.failure_class, '')
           FROM batch.trigger_request request
           JOIN batch.workflow_run workflow_run ON workflow_run.tenant_id = request.tenant_id
             AND workflow_run.trigger_request_id = request.id
           JOIN batch.workflow_node_run node_run ON node_run.workflow_run_id = workflow_run.id
           JOIN batch.job_instance instance ON instance.tenant_id = workflow_run.tenant_id
             AND instance.id = node_run.job_instance_id
           JOIN batch.job_task task ON task.job_instance_id = instance.id
           WHERE request.tenant_id = :'tenant_id'
             AND request.request_id = :'http_request_id'
           ORDER BY (task.task_status = 'FAILED') DESC,
                    (coalesce(task.error_code, '') <> '') DESC,
                    task.id DESC
           LIMIT 1
       ),
       (
           SELECT instance.instance_status || ':' || coalesce(task.error_code, '')
               || ':' || coalesce(task.failure_class, '')
           FROM batch.trigger_request request
           JOIN batch.workflow_run workflow_run ON workflow_run.tenant_id = request.tenant_id
             AND workflow_run.trigger_request_id = request.id
           JOIN batch.workflow_node_run node_run ON node_run.workflow_run_id = workflow_run.id
           JOIN batch.job_instance instance ON instance.tenant_id = workflow_run.tenant_id
             AND instance.id = node_run.job_instance_id
           JOIN batch.job_task task ON task.job_instance_id = instance.id
           WHERE request.tenant_id = :'tenant_id'
             AND request.request_id = :'timeout_request_id'
           ORDER BY (task.task_status = 'FAILED') DESC,
                    (coalesce(task.error_code, '') <> '') DESC,
                    task.id DESC
           LIMIT 1
       ),
       (
           SELECT instance.instance_status || ':' || task.task_status
               || ':' || task.cancel_requested::text
           FROM batch.trigger_request request
           JOIN batch.workflow_run workflow_run ON workflow_run.tenant_id = request.tenant_id
             AND workflow_run.trigger_request_id = request.id
           JOIN batch.workflow_node_run node_run ON node_run.workflow_run_id = workflow_run.id
           JOIN batch.job_instance instance ON instance.tenant_id = workflow_run.tenant_id
             AND instance.id = node_run.job_instance_id
           JOIN batch.job_task task ON task.job_instance_id = instance.id
           WHERE request.tenant_id = :'tenant_id'
             AND request.request_id = :'cancel_request_id'
           ORDER BY task.id DESC
           LIMIT 1
       );
