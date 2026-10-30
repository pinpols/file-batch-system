-- 仅在 occurrence 规划器完成持久化后，读取 Quartz 实际的下一次触发时间。
SELECT trigger.next_fire_time
FROM quartz.qrtz_triggers trigger
JOIN batch.workflow_definition workflow
  ON workflow.tenant_id = 'ta'
 AND workflow.workflow_code = 'WF-TA_TRIGGER_STAGE6C_MISFIRE'
JOIN batch.scheduled_occurrence occurrence
  ON occurrence.tenant_id = workflow.tenant_id
 AND occurrence.workflow_definition_id = workflow.id
 AND occurrence.scheduled_fire_time = to_timestamp(trigger.next_fire_time / 1000.0)
 AND occurrence.occurrence_status = 'PLANNED'
WHERE trigger.trigger_group = 'batch-trigger'
  AND trigger.trigger_state = 'PAUSED'
  AND trigger.trigger_name = 'ta:WF:WF-TA_TRIGGER_STAGE6C_MISFIRE';
