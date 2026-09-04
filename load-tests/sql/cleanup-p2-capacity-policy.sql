DELETE FROM batch.tenant_quota_policy
WHERE tenant_id = :'capacity_tenant_id'
  AND policy_code = 'p2-capacity-profile'
  AND description = 'Ephemeral P2 unbounded-admission capacity policy';

DELETE FROM batch.job_definition
WHERE tenant_id = :'capacity_tenant_id'
  AND job_code = 'atomic_sql_demo'
  AND description = 'P2 local capacity clone with unbounded admission policy';

DELETE FROM batch.tenant
WHERE tenant_id = :'capacity_tenant_id'
  AND description = 'Ephemeral P2 unbounded-admission load-test tenant';
