DELETE FROM batch.tenant_quota_policy
WHERE tenant_id = :'capacity_tenant_id'
  AND policy_code = 'p2-capacity-profile'
  AND description = 'Ephemeral P2 unbounded-admission capacity policy';

DELETE FROM batch.job_definition
WHERE tenant_id = :'capacity_tenant_id'
  AND job_code = 'atomic_sql_demo'
  AND description = 'P2 local capacity clone with unbounded admission policy'
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
