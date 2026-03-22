-- Test seed data: worker registry entries for orchestrator integration tests
-- Use in tests that need pre-existing ONLINE workers.

INSERT INTO batch.worker_registry
  (tenant_id, worker_code, worker_group, capability_tags, resource_tag,
   status, heartbeat_at, current_load, drain_started_at, drain_deadline_at)
VALUES
  ('t1', 'worker-seed-001', 'IMPORT', '{"import":true}', null,
   'ONLINE', now(), 0, null, null),
  ('t1', 'worker-seed-002', 'EXPORT', '{"export":true}', null,
   'ONLINE', now(), 0, null, null),
  ('t1', 'worker-seed-003', 'DEFAULT', '{"import":true,"export":true}', null,
   'ONLINE', now(), 0, null, null),
  ('t1', 'worker-seed-drain', 'DEFAULT', '{}', null,
   'DRAINING', now() - interval '10 minutes', 0,
   now() - interval '10 minutes', now() - interval '1 minute')
ON CONFLICT DO NOTHING;
