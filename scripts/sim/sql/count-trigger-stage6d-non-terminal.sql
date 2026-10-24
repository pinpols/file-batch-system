SELECT count(DISTINCT request.id) FROM batch.trigger_request request
LEFT JOIN batch.workflow_run workflow_run ON workflow_run.tenant_id = request.tenant_id
  AND workflow_run.trigger_request_id = request.id
WHERE request.tenant_id = :'tenant_id' AND request.request_id LIKE :'request_prefix' || '%'
  AND coalesce(workflow_run.run_status, request.request_status)
      NOT IN ('SUCCESS','FAILED','PARTIAL_FAILED','CANCELLED','TERMINATED');
