SELECT workflow.id
FROM batch.workflow_run workflow
JOIN batch.trigger_request request
  ON request.tenant_id = workflow.tenant_id AND request.id = workflow.trigger_request_id
WHERE request.launch_payload #>> '{params,metadata,runId}' = :'run_id'
ORDER BY workflow.id;
