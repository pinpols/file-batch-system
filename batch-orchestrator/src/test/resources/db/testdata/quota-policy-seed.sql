-- Test seed data: quota policies for orchestrator integration tests
-- Use in tests that need pre-existing quota policy configurations.

INSERT INTO batch.tenant_quota_policy
  (tenant_id, policy_code, enabled,
   max_running_jobs_per_tenant, max_partitions_per_tenant, max_qps_per_tenant,
   fair_share_weight, fair_share_group, group_shared_max_running_jobs,
   burst_limit, partition_burst_limit, quota_reset_policy,
   created_at, updated_at)
VALUES
  ('t1', 'DEFAULT', true,
   100, 500, 100,
   1, 'DEFAULT', 200,
   20, 50, 'NONE',
   now(), now()),
  ('t1', 'SLIDING_WINDOW_POLICY', true,
   50, 250, 50,
   1, 'DEFAULT', 100,
   10, 20, 'SLIDING_WINDOW',
   now(), now()),
  ('t1', 'CALENDAR_DAY_POLICY', true,
   50, 250, 50,
   1, 'DEFAULT', 100,
   10, 20, 'CALENDAR_DAY',
   now(), now())
ON CONFLICT DO NOTHING;
