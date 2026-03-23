-- Test seed data: job instances for orchestrator integration tests
-- Use this in tests that need pre-existing job data.
-- All records use tenant_id='t1' to match default test tenant.

INSERT INTO batch.job_definition
  (tenant_id, job_code, job_name, job_type, schedule_type, trigger_mode, timezone,
   retry_policy, retry_max_count, created_at, updated_at)
VALUES
  ('t1', 'TEST_IMPORT_JOB', 'Test Import Job', 'IMPORT', 'MANUAL', 'MANUAL', 'UTC',
   'FIXED', 3, now(), now()),
  ('t1', 'TEST_EXPORT_JOB', 'Test Export Job', 'EXPORT', 'MANUAL', 'MANUAL', 'UTC',
   'EXPONENTIAL', 2, now(), now()),
  ('t1', 'TEST_DISPATCH_JOB', 'Test Dispatch Job', 'DISPATCH', 'MANUAL', 'MANUAL', 'UTC',
   'NONE', 0, now(), now())
ON CONFLICT DO NOTHING;
