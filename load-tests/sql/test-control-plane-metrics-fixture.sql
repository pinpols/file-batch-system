-- 控制面指标查询的最小 PostgreSQL fixture，仅在独立空数据库执行。
-- batch schema 已存在时立即失败；不修改现有应用库，不代表 Flyway 或完整业务链路验收。
-- psql 变量 metrics_case 决定被测状态组合。
BEGIN;
CREATE SCHEMA batch;
CREATE TABLE batch.trigger_request (
    id bigint, tenant_id text, request_id text, workflow_definition_id bigint,
    biz_date date, related_job_instance_id bigint, dedup_key text, trace_id text, launch_payload jsonb,
    request_status text DEFAULT 'LAUNCHED'
);
CREATE TABLE batch.workflow_run (
    id bigint, tenant_id text, trigger_request_id bigint, workflow_definition_id bigint,
    biz_date date, run_status text, related_job_instance_id bigint
);
CREATE TABLE batch.workflow_node_run (
    id bigint, workflow_run_id bigint, node_type text, node_status text, job_instance_id bigint
);
CREATE TABLE batch.job_instance (
    id bigint, tenant_id text, trigger_request_id bigint, biz_date date, instance_status text,
    params_snapshot jsonb
);

INSERT INTO batch.trigger_request (id, tenant_id, request_id, workflow_definition_id, biz_date, related_job_instance_id) VALUES
    (1, 'tenant-a', 'metrics-run-1', 500, '2026-10-18', NULL),
    (2, 'tenant-a', 'metrics-run-2', 500, '2026-10-19', NULL),
    (3, 'tenant-b', 'metrics-run-3', 500, '2026-10-18', NULL),
    (4, 'tenant-a', 'metrics-run-child-trigger-4', 500, '2026-10-18', NULL);
UPDATE batch.trigger_request
SET launch_payload = jsonb_build_object('params', jsonb_build_object('metadata',
    jsonb_build_object('runId', CASE WHEN id IN (1, 2) THEN 'metrics-run' ELSE 'metrics-run-child' END)));
-- 真实的子任务启动会继承 runId，但自身没有对应的 Workflow 定义。
INSERT INTO batch.trigger_request (id,tenant_id,request_id,workflow_definition_id,biz_date,launch_payload)
VALUES (5,'tenant-a','metrics-owned-child',NULL,'2026-10-18',
'{"params":{"metadata":{"runId":"metrics-run"},"_parentWorkflowRunId":10}}');
UPDATE batch.trigger_request SET request_status = 'WAITING'
WHERE id = 2 AND :'metrics_case' = 'request-waiting';
INSERT INTO batch.workflow_run (id, tenant_id, trigger_request_id, workflow_definition_id, biz_date, run_status) VALUES
    (10, 'tenant-a', 1, 500, '2026-10-18', 'SUCCESS'),
    (11, CASE WHEN :'metrics_case' = 'foreign-root' THEN 'tenant-b' ELSE 'tenant-a' END,
     2, CASE WHEN :'metrics_case' = 'wrong-definition' THEN 999 ELSE 500 END,
     '2026-10-19', CASE WHEN :'metrics_case' = 'root-running' THEN 'RUNNING'
                       WHEN :'metrics_case' = 'root-failed' THEN 'FAILED' ELSE 'SUCCESS' END),
    (12, 'tenant-b', 3, 500, '2026-10-18', 'SUCCESS'),
    (13, 'tenant-a', 4, 500, '2026-10-18', 'SUCCESS');
INSERT INTO batch.workflow_node_run VALUES
    (20, 10, 'JOB', 'SUCCESS', 100),
    (22, 12, 'JOB', 'SUCCESS', 102),
    (23, 13, 'JOB', 'SUCCESS', 103);
INSERT INTO batch.workflow_node_run
SELECT 21, 11, 'JOB',
       CASE WHEN :'metrics_case' = 'child-running' THEN 'RUNNING'
            WHEN :'metrics_case' = 'node-failed' THEN 'FAILED' ELSE 'SUCCESS' END, 101
WHERE :'metrics_case' <> 'missing-node';
INSERT INTO batch.job_instance (id, tenant_id, trigger_request_id, biz_date, instance_status) VALUES
    (100, 'tenant-a', 1, '2026-10-18', 'SUCCESS'),
    (102, 'tenant-b', 3, '2026-10-18', 'SUCCESS'),
    (103, 'tenant-a', 4, '2026-10-18', 'SUCCESS');
INSERT INTO batch.job_instance (id, tenant_id, trigger_request_id, biz_date, instance_status)
SELECT 101,
       CASE WHEN :'metrics_case' = 'foreign-job' THEN 'tenant-b' ELSE 'tenant-a' END,
       2, '2026-10-19',
       CASE WHEN :'metrics_case' = 'child-running' THEN 'RUNNING' ELSE 'SUCCESS' END
WHERE :'metrics_case' <> 'missing-job';
UPDATE batch.job_instance SET params_snapshot = jsonb_build_object('requestParams',
    jsonb_build_object('metadata', jsonb_build_object('runId',
        CASE WHEN id IN (100, 101) THEN 'metrics-run' ELSE 'metrics-run-child' END),
        'batchKey', CASE WHEN id = 100 THEN 'metrics-run-owned'
                        WHEN id = 103 THEN 'metrics-run-child-owned' ELSE NULL END));
COMMIT;
