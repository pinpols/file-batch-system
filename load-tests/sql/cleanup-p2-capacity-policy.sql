-- p2capacity 是容量画像专用的短生命周期租户。只有确认没有实例和 Trigger 请求后，
-- 才允许回收异常中断或旧版清理顺序留下的无主运行记录。
DELETE FROM batch.event_outbox_retry retry
USING batch.outbox_event event
WHERE retry.outbox_event_id = event.id
  AND event.tenant_id = :'capacity_tenant_id'
  AND NOT EXISTS (SELECT 1 FROM batch.job_instance WHERE tenant_id = :'capacity_tenant_id')
  AND NOT EXISTS (SELECT 1 FROM batch.trigger_request WHERE tenant_id = :'capacity_tenant_id');

DELETE FROM batch.event_delivery_log delivery
USING batch.outbox_event event
WHERE delivery.outbox_event_id = event.id
  AND event.tenant_id = :'capacity_tenant_id'
  AND NOT EXISTS (SELECT 1 FROM batch.job_instance WHERE tenant_id = :'capacity_tenant_id')
  AND NOT EXISTS (SELECT 1 FROM batch.trigger_request WHERE tenant_id = :'capacity_tenant_id');

DELETE FROM batch.outbox_event
WHERE tenant_id = :'capacity_tenant_id'
  AND NOT EXISTS (SELECT 1 FROM batch.job_instance WHERE tenant_id = :'capacity_tenant_id')
  AND NOT EXISTS (SELECT 1 FROM batch.trigger_request WHERE tenant_id = :'capacity_tenant_id');

DELETE FROM batch.asset_partition asset
USING batch.result_version result
WHERE asset.result_version_id = result.id
  AND result.tenant_id = :'capacity_tenant_id'
  AND NOT EXISTS (SELECT 1 FROM batch.job_instance WHERE tenant_id = :'capacity_tenant_id')
  AND NOT EXISTS (SELECT 1 FROM batch.trigger_request WHERE tenant_id = :'capacity_tenant_id');

DELETE FROM batch.result_version
WHERE tenant_id = :'capacity_tenant_id'
  AND NOT EXISTS (SELECT 1 FROM batch.job_instance WHERE tenant_id = :'capacity_tenant_id')
  AND NOT EXISTS (SELECT 1 FROM batch.trigger_request WHERE tenant_id = :'capacity_tenant_id');

DELETE FROM batch.job_instance_dedup_key
WHERE tenant_id = :'capacity_tenant_id'
  AND NOT EXISTS (SELECT 1 FROM batch.job_instance WHERE tenant_id = :'capacity_tenant_id')
  AND NOT EXISTS (SELECT 1 FROM batch.trigger_request WHERE tenant_id = :'capacity_tenant_id');

DELETE FROM batch.outbox_event_dedup_key
WHERE tenant_id = :'capacity_tenant_id'
  AND NOT EXISTS (SELECT 1 FROM batch.job_instance WHERE tenant_id = :'capacity_tenant_id')
  AND NOT EXISTS (SELECT 1 FROM batch.trigger_request WHERE tenant_id = :'capacity_tenant_id');

DELETE FROM batch.tenant_quota_policy
WHERE tenant_id = :'capacity_tenant_id'
  AND policy_code = 'p2-capacity-profile'
  AND description = 'Ephemeral P2 unbounded-admission capacity policy';

DELETE FROM batch.job_definition
WHERE tenant_id = :'capacity_tenant_id'
  AND job_code = 'atomic_sql_demo'
  AND description IN (
      'P2 local capacity clone with unbounded admission policy',
      'P2 local capacity definition with unbounded admission policy'
  )
  -- 上一轮异常中断会按设计保留现场；不能因本轮收尾而违反历史实例的 FK。
  -- 无引用时才删除夹具定义，下轮 prepare 会幂等复建。
  AND NOT EXISTS (
      SELECT 1
      FROM batch.job_instance ji
      WHERE ji.job_definition_id = batch.job_definition.id
  );

DELETE FROM batch.tenant
WHERE tenant_id = :'capacity_tenant_id'
  AND description = 'Ephemeral P2 unbounded-admission load-test tenant'
  AND NOT EXISTS (
      SELECT 1
      FROM batch.job_instance ji
      WHERE ji.tenant_id = batch.tenant.tenant_id
  );
