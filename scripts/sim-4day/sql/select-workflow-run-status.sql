SELECT workflow_run.run_status
FROM batch.workflow_run workflow_run
JOIN batch.trigger_request trigger_request
  ON trigger_request.tenant_id = workflow_run.tenant_id
 AND trigger_request.id = workflow_run.trigger_request_id
WHERE trigger_request.tenant_id = :'tenant_id'
  AND trigger_request.request_id = :'request_id'
ORDER BY workflow_run.id DESC
LIMIT 1;
