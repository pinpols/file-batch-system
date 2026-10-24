SELECT request.trigger_type, workflow.workflow_code, request.request_status, count(*)
FROM batch.trigger_request request
LEFT JOIN batch.workflow_definition workflow
  ON workflow.tenant_id = request.tenant_id
 AND workflow.id = request.workflow_definition_id
WHERE request.tenant_id = :'tenant_id'
  AND (request.request_id LIKE :'batch_prefix' || '%'
       OR request.created_at >= :'start_ts'::timestamptz)
GROUP BY request.trigger_type, workflow.workflow_code, request.request_status
ORDER BY request.trigger_type, workflow.workflow_code, request.request_status;
