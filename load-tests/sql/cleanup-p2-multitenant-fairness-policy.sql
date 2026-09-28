-- P2 压测档案独占这些隔离租户和配置记录。
-- 清理条件采用精确匹配，不得影响租户自行维护的定义。
DELETE FROM batch.tenant_quota_policy
WHERE policy_code = 'p2-fairness-profile'
  AND fair_share_group = 'p2-load-fairness'
  AND description = 'Ephemeral P2 fairness load-test policy'
  AND tenant_id IN ('p2fa', 'p2fb', 'p2fc');

DELETE FROM batch.job_definition
WHERE tenant_id IN ('p2fa', 'p2fb', 'p2fc')
  AND job_code = 'atomic_sql_demo'
  AND description = 'P2 local multi-tenant fairness clone of default atomic_sql_demo';

DELETE FROM batch.tenant
WHERE tenant_id IN ('p2fa', 'p2fb', 'p2fc')
  AND description = 'Ephemeral P2 fairness load-test tenant';
