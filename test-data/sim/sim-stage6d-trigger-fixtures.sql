-- Stage 6d trigger 压力 fixtures。
-- 必需的 psql 变量:batch_no, biz_date
--
-- 本 fixture 假定 sim-stage6c-trigger-fixtures.sql 已经创建了
-- TA_TRIGGER_STAGE6C_* 定时 Workflow 与 Job 节点。Stage6d 收紧 Workflow cron
-- 节奏；Pipeline 阶段由系统 Job 类型绑定提供。

DELETE FROM batch.trigger_misfire_pending
WHERE tenant_id = 'ta'
  AND workflow_definition_id IN (
      SELECT id FROM batch.workflow_definition
      WHERE tenant_id = 'ta'
        AND workflow_code IN ('WF-TA_TRIGGER_STAGE6C_SCHEDULED', 'WF-TA_TRIGGER_STAGE6C_MISFIRE')
  );

DELETE FROM batch.trigger_request
WHERE tenant_id = 'ta'
  AND request_id LIKE :'batch_no' || '%';

DELETE FROM batch.trigger_outbox_event
WHERE tenant_id = 'ta'
  AND request_id LIKE :'batch_no' || '%';

UPDATE batch.workflow_definition
SET schedule_expr = '0/2 * * * * ?',
    schedule_enabled = true,
    description = 'Stage6d high-frequency scheduled trigger process',
    updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'ta'
  AND workflow_code = 'WF-TA_TRIGGER_STAGE6C_SCHEDULED';

UPDATE batch.workflow_definition
SET schedule_enabled = true,
    updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'ta'
  AND workflow_code = 'WF-TA_TRIGGER_STAGE6C_MISFIRE';
