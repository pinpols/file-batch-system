-- Test seed data: job instances for orchestrator integration tests
-- Use this in tests that need pre-existing job data.
-- All records use tenant_id='t1' to match default test tenant.

INSERT INTO batch.job_definition
  (tenant_id, job_code, job_name, job_type, trigger_type, retry_policy, retry_max_count,
   sla_deadline_offset_seconds, expected_duration_seconds, is_active, created_at, updated_at)
VALUES
  ('t1', 'TEST_IMPORT_JOB', 'Test Import Job', 'IMPORT', 'MANUAL', 'FIXED', 3,
   3600, 600, true, now(), now()),
  ('t1', 'TEST_EXPORT_JOB', 'Test Export Job', 'EXPORT', 'MANUAL', 'EXPONENTIAL', 2,
   7200, 1200, true, now(), now()),
  ('t1', 'TEST_DISPATCH_JOB', 'Test Dispatch Job', 'DISPATCH', 'MANUAL', 'NONE', 0,
   null, null, true, now(), now())
ON CONFLICT DO NOTHING;
