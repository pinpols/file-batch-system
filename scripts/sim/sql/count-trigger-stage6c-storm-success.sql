SELECT count(DISTINCT request.id) FROM batch.trigger_request request
JOIN batch.workflow_run workflow_run ON workflow_run.tenant_id = request.tenant_id
  AND workflow_run.trigger_request_id = request.id
WHERE request.tenant_id = :'tenant_id' AND request.request_id LIKE :'batch_prefix' || '-storm-%'
  AND workflow_run.run_status = 'SUCCESS';
