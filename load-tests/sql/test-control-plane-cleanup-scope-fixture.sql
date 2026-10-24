-- 先准备 metrics fixture。暂存在实际系统中位于业务库；这里放同名诱饵表，
-- 验证控制面清理不会猜测关联并跨越数据库所有权删除它，不执行整棵运行树删除。
CREATE TABLE batch.job_task (id bigint, tenant_id text, job_instance_id bigint, task_payload jsonb,
    task_status text DEFAULT 'SUCCESS');
CREATE TABLE batch.process_staging (tenant_id text, batch_key text);
INSERT INTO batch.job_task (id, tenant_id, job_instance_id, task_payload) VALUES
    (200, 'tenant-a', 101, '{"batchKey":"metrics-run-generated"}');
UPDATE batch.job_task SET task_status = 'RUNNING' WHERE :'metrics_case' = 'task-running';
INSERT INTO batch.process_staging VALUES
    ('tenant-a', 'metrics-run-owned'),
    ('tenant-a', 'metrics-run-generated'),
    ('tenant-a', 'metrics-run-child-owned'),
    ('tenant-b', 'metrics-run-owned');
