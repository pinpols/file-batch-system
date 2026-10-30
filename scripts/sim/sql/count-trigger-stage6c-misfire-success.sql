SELECT count(*) FROM batch.workflow_run run
JOIN batch.workflow_definition workflow
  ON workflow.tenant_id = run.tenant_id
 AND workflow.id = run.workflow_definition_id
WHERE run.tenant_id = :'tenant_id'
  AND workflow.workflow_code = 'WF-TA_TRIGGER_STAGE6C_MISFIRE'
  AND run.run_status = 'SUCCESS'
  AND run.created_at >= :'start_ts'::timestamptz;
