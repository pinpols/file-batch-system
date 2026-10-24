SELECT concat_ws('|', pending.status, occurrence.occurrence_status,
                 run.run_status, instance.instance_status,
                 CASE WHEN run.data_interval_start = pending.scheduled_fire_time
                           AND run.data_interval_end > run.data_interval_start
                           AND instance.data_interval_start = run.data_interval_start
                           AND instance.data_interval_end = run.data_interval_end
                           AND instance.biz_date = run.biz_date
                           AND request.biz_date = run.biz_date
                           AND run.workflow_version_snapshot::text = request.launch_payload -> 'params' ->> 'workflowVersion'
                      THEN 'SNAPSHOT_OK' ELSE 'SNAPSHOT_MISMATCH' END,
                 CASE WHEN NOT definition.enabled THEN 'WORKFLOW_DISABLED' ELSE 'WORKFLOW_ENABLED' END)
FROM batch.trigger_misfire_pending pending
JOIN batch.scheduled_occurrence occurrence
  ON occurrence.tenant_id = pending.tenant_id
 AND occurrence.workflow_definition_id = pending.workflow_definition_id
 AND occurrence.scheduled_fire_time = pending.scheduled_fire_time
JOIN batch.trigger_request request
  ON request.tenant_id = pending.tenant_id AND request.id = pending.catch_up_request_id
JOIN batch.workflow_run run
  ON run.tenant_id = pending.tenant_id
 AND run.trigger_request_id = pending.catch_up_request_id
JOIN batch.workflow_definition definition
  ON definition.tenant_id = run.tenant_id AND definition.id = run.workflow_definition_id
JOIN batch.workflow_node_run node
  ON node.workflow_run_id = run.id AND node.node_type = 'JOB'
JOIN batch.job_instance instance
  ON instance.tenant_id = run.tenant_id AND instance.id = node.job_instance_id
WHERE pending.tenant_id = :'tenant_id'
  AND pending.scheduled_fire_time = to_timestamp(:'scheduled_fire_millis'::bigint / 1000.0)
  AND pending.workflow_definition_id = (
      SELECT id FROM batch.workflow_definition
      WHERE tenant_id = :'tenant_id' AND workflow_code = 'WF-TA_TRIGGER_STAGE6C_MISFIRE'
      ORDER BY version DESC LIMIT 1
  )
ORDER BY node.id DESC LIMIT 1;
