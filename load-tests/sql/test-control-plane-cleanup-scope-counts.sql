SELECT 'roots|' || coalesce(string_agg(id::text, ',' ORDER BY id), '') FROM p2_cleanup_workflow_run_ids;
SELECT 'jobs|' || coalesce(string_agg(id::text, ',' ORDER BY id), '') FROM p2_cleanup_job_instance_ids;
SELECT 'owned_staging|' || count(*) FROM batch.process_staging
WHERE tenant_id = 'tenant-a' AND batch_key IN ('metrics-run-owned', 'metrics-run-generated');
SELECT 'foreign_staging|' || count(*) FROM batch.process_staging
WHERE tenant_id = 'tenant-b' AND batch_key = 'metrics-run-owned';
SELECT 'other_staging|' || count(*) FROM batch.process_staging
WHERE tenant_id = 'tenant-a' AND batch_key = 'metrics-run-child-owned';
