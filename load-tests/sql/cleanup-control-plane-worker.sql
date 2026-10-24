-- DANGER: 删除本轮压测控制面数据，仅限专用隔离压测库。
BEGIN;

-- 按冻结的完整 runId 标记选择归属；请求号或 trace 的前缀不能证明运行归属。
CREATE TEMP TABLE p2_cleanup_job_instance_ids (id bigint PRIMARY KEY) ON COMMIT DROP;
CREATE TEMP TABLE p2_cleanup_pipeline_instance_ids (id bigint PRIMARY KEY) ON COMMIT DROP;
CREATE TEMP TABLE p2_cleanup_workflow_run_ids (id bigint PRIMARY KEY) ON COMMIT DROP;
CREATE TEMP TABLE p2_cleanup_trigger_request_ids (id bigint PRIMARY KEY) ON COMMIT DROP;

INSERT INTO p2_cleanup_trigger_request_ids (id)
SELECT id FROM batch.trigger_request
WHERE launch_payload #>> '{params,metadata,runId}' = :'run_id';

INSERT INTO p2_cleanup_workflow_run_ids (id)
SELECT workflow.id
FROM batch.workflow_run workflow
JOIN batch.trigger_request request
  ON request.tenant_id = workflow.tenant_id AND request.id = workflow.trigger_request_id
WHERE request.launch_payload #>> '{params,metadata,runId}' = :'run_id';

INSERT INTO p2_cleanup_job_instance_ids (id)
SELECT instance.id FROM batch.job_instance instance
WHERE coalesce(instance.params_snapshot #>> '{requestParams,metadata,runId}',
               instance.params_snapshot #>> '{effectiveParams,metadata,runId}',
               instance.params_snapshot #>> '{metadata,runId}',
               instance.params_snapshot #>> '{runId}') = :'run_id'
UNION
SELECT node.job_instance_id
FROM batch.workflow_node_run node
JOIN batch.workflow_run workflow ON workflow.id = node.workflow_run_id
JOIN batch.job_instance instance
  ON instance.id = node.job_instance_id
 AND instance.tenant_id = workflow.tenant_id AND instance.biz_date = workflow.biz_date
WHERE workflow.id IN (SELECT id FROM p2_cleanup_workflow_run_ids);

-- 运行中、暂停或等待中的测试工作应先停发并收敛，不能通过删状态行伪装清理成功。
-- 此安全门不授权跨业务库删除 PROCESS 暂存；其生命周期按专门方案验证。
DO $cleanup_guard$
BEGIN
  IF EXISTS (
      SELECT 1 FROM batch.trigger_request
      WHERE id IN (SELECT id FROM p2_cleanup_trigger_request_ids)
        AND coalesce(request_status, '') NOT IN ('LAUNCHED', 'REJECTED', 'DUPLICATE', 'FORWARD_FAILED', 'GIVE_UP')
  ) OR EXISTS (
      SELECT 1 FROM batch.workflow_run
      WHERE id IN (SELECT id FROM p2_cleanup_workflow_run_ids)
        AND coalesce(run_status, '') NOT IN ('SUCCESS', 'FAILED', 'TERMINATED', 'SUCCESS_DRY_RUN', 'FAILED_DRY_RUN')
  ) OR EXISTS (
      SELECT 1 FROM batch.job_instance
      WHERE id IN (SELECT id FROM p2_cleanup_job_instance_ids)
        AND coalesce(instance_status, '') NOT IN ('SUCCESS', 'FAILED', 'PARTIAL_FAILED', 'CANCELLED', 'TERMINATED', 'SUCCESS_DRY_RUN', 'FAILED_DRY_RUN')
  ) OR EXISTS (
      SELECT 1 FROM batch.job_task
      WHERE job_instance_id IN (SELECT id FROM p2_cleanup_job_instance_ids)
        AND coalesce(task_status, '') NOT IN ('SUCCESS', 'FAILED', 'CANCELLED', 'TERMINATED')
  ) THEN
    RAISE EXCEPTION 'load-test cleanup blocked: selected run still has active requests, roots, jobs or tasks';
  END IF;
END;
$cleanup_guard$;

-- 清理压测失败场景时，worker 可能仍在为本轮 pipeline 写 step run。先锁住父行，
-- 避免删完子表到删父表之间又插入新的 pipeline_step_run，导致 FK 失败回滚。
INSERT INTO p2_cleanup_pipeline_instance_ids (id)
SELECT pi.id
FROM batch.pipeline_instance pi
WHERE pi.related_job_instance_id IN (SELECT id FROM p2_cleanup_job_instance_ids)
FOR UPDATE;

WITH ji AS (
  SELECT id FROM p2_cleanup_job_instance_ids
),
jt AS (
  SELECT id FROM batch.job_task WHERE job_instance_id IN (SELECT id FROM ji)
),
jp AS (
  SELECT id FROM batch.job_partition WHERE job_instance_id IN (SELECT id FROM ji)
),
oe AS (
  SELECT id
  FROM batch.outbox_event
  WHERE (
      (aggregate_type = 'JOB_INSTANCE' AND aggregate_id IN (SELECT id FROM ji))
      OR (aggregate_type = 'JOB_PARTITION' AND aggregate_id IN (SELECT id FROM jp))
      OR (aggregate_type = 'JOB_TASK' AND aggregate_id IN (SELECT id FROM jt))
    )
)
DELETE FROM batch.event_outbox_retry
WHERE outbox_event_id IN (SELECT id FROM oe);

WITH ji AS (
  SELECT id FROM p2_cleanup_job_instance_ids
),
jt AS (
  SELECT id FROM batch.job_task WHERE job_instance_id IN (SELECT id FROM ji)
),
jp AS (
  SELECT id FROM batch.job_partition WHERE job_instance_id IN (SELECT id FROM ji)
),
oe AS (
  SELECT id
  FROM batch.outbox_event
  WHERE (
      (aggregate_type = 'JOB_INSTANCE' AND aggregate_id IN (SELECT id FROM ji))
      OR (aggregate_type = 'JOB_PARTITION' AND aggregate_id IN (SELECT id FROM jp))
      OR (aggregate_type = 'JOB_TASK' AND aggregate_id IN (SELECT id FROM jt))
    )
)
DELETE FROM batch.event_delivery_log
WHERE outbox_event_id IN (SELECT id FROM oe);

-- 两张全局幂等账本在生产中按 retention runbook 长期保留，但容量画像使用专用租户并会
-- 反复创建一次性 key。若只删业务行，连续压测会永久放大 PK 索引，使后一轮比前一轮更慢。
-- 必须在 outbox_event 尚未删除时按本轮事件的完整唯一键精确回收，不能按时间或租户模糊删除。
WITH ji AS (
  SELECT id FROM p2_cleanup_job_instance_ids
),
jt AS (
  SELECT id FROM batch.job_task WHERE job_instance_id IN (SELECT id FROM ji)
),
jp AS (
  SELECT id FROM batch.job_partition WHERE job_instance_id IN (SELECT id FROM ji)
),
oe AS (
  SELECT tenant_id, event_key
  FROM batch.outbox_event
  WHERE (
      (aggregate_type = 'JOB_INSTANCE' AND aggregate_id IN (SELECT id FROM ji))
      OR (aggregate_type = 'JOB_PARTITION' AND aggregate_id IN (SELECT id FROM jp))
      OR (aggregate_type = 'JOB_TASK' AND aggregate_id IN (SELECT id FROM jt))
    )
)
DELETE FROM batch.outbox_event_dedup_key ledger
USING oe
WHERE ledger.tenant_id = oe.tenant_id
  AND ledger.event_key = oe.event_key;

WITH ji AS (
  SELECT id FROM p2_cleanup_job_instance_ids
),
jt AS (
  SELECT id FROM batch.job_task WHERE job_instance_id IN (SELECT id FROM ji)
),
jp AS (
  SELECT id FROM batch.job_partition WHERE job_instance_id IN (SELECT id FROM ji)
)
DELETE FROM batch.outbox_event
WHERE (
    (aggregate_type = 'JOB_INSTANCE' AND aggregate_id IN (SELECT id FROM ji))
    OR (aggregate_type = 'JOB_PARTITION' AND aggregate_id IN (SELECT id FROM jp))
    OR (aggregate_type = 'JOB_TASK' AND aggregate_id IN (SELECT id FROM jt))
  );

WITH ji AS (
  SELECT id FROM p2_cleanup_job_instance_ids
),
jt AS (
  SELECT id FROM batch.job_task WHERE job_instance_id IN (SELECT id FROM ji)
)
DELETE FROM batch.job_step_instance WHERE job_task_id IN (SELECT id FROM jt);

WITH ji AS (
  SELECT id FROM p2_cleanup_pipeline_instance_ids
)
DELETE FROM batch.pipeline_step_run
WHERE pipeline_instance_id IN (SELECT id FROM ji);

DELETE FROM batch.pipeline_progress
WHERE pipeline_instance_id IN (SELECT id FROM p2_cleanup_pipeline_instance_ids);

WITH ji AS (
  SELECT id FROM p2_cleanup_pipeline_instance_ids
)
DELETE FROM batch.file_dispatch_record
WHERE pipeline_instance_id IN (SELECT id FROM ji);

WITH ji AS (
  SELECT id FROM p2_cleanup_pipeline_instance_ids
)
DELETE FROM batch.pipeline_instance WHERE id IN (SELECT id FROM ji);

DELETE FROM batch.workflow_node_run
WHERE workflow_run_id IN (SELECT id FROM p2_cleanup_workflow_run_ids);

DELETE FROM batch.workflow_run
WHERE id IN (SELECT id FROM p2_cleanup_workflow_run_ids);

WITH ji AS (
  SELECT id FROM p2_cleanup_job_instance_ids
)
DELETE FROM batch.job_execution_log WHERE job_instance_id IN (SELECT id FROM ji);

WITH ji AS (
  SELECT id FROM p2_cleanup_job_instance_ids
)
DELETE FROM batch.compensation_command WHERE related_job_instance_id IN (SELECT id FROM ji);

-- result_version 没有指向 job_instance 的外键，单独删除实例会遗留每次压测产生的
-- 结果版本。先删除资产物化指针，再按本轮实例清理版本，避免重复压测持续放大索引、
-- 污染不同轮次之间的性能对比。
WITH ji AS (
  SELECT id FROM p2_cleanup_job_instance_ids
),
rv AS (
  SELECT id FROM batch.result_version WHERE job_instance_id IN (SELECT id FROM ji)
)
DELETE FROM batch.asset_partition WHERE result_version_id IN (SELECT id FROM rv);

WITH ji AS (
  SELECT id FROM p2_cleanup_job_instance_ids
)
DELETE FROM batch.result_version WHERE job_instance_id IN (SELECT id FROM ji);

WITH ji AS (
  SELECT id FROM p2_cleanup_job_instance_ids
),
jt AS (
  SELECT id FROM batch.job_task WHERE job_instance_id IN (SELECT id FROM ji)
),
jp AS (
  SELECT id FROM batch.job_partition WHERE job_instance_id IN (SELECT id FROM ji)
)
DELETE FROM batch.dead_letter_task
WHERE (
    (source_type = 'JOB_INSTANCE' AND source_id IN (SELECT id FROM ji))
    OR (source_type = 'JOB_PARTITION' AND source_id IN (SELECT id FROM jp))
    OR (source_type = 'JOB_TASK' AND source_id IN (SELECT id FROM jt))
  );

WITH ji AS (
  SELECT id FROM p2_cleanup_job_instance_ids
),
jt AS (
  SELECT id FROM batch.job_task WHERE job_instance_id IN (SELECT id FROM ji)
),
jp AS (
  SELECT id FROM batch.job_partition WHERE job_instance_id IN (SELECT id FROM ji)
)
DELETE FROM batch.retry_schedule
WHERE (
    (related_type = 'JOB_INSTANCE' AND related_id IN (SELECT id FROM ji))
    OR (related_type = 'JOB_PARTITION' AND related_id IN (SELECT id FROM jp))
    OR (related_type = 'JOB_TASK' AND related_id IN (SELECT id FROM jt))
  );

WITH ji AS (
  SELECT id FROM p2_cleanup_job_instance_ids
)
DELETE FROM batch.job_task WHERE job_instance_id IN (SELECT id FROM ji);

WITH ji AS (
  SELECT id FROM p2_cleanup_job_instance_ids
)
DELETE FROM batch.job_partition WHERE job_instance_id IN (SELECT id FROM ji);

UPDATE batch.trigger_request
SET related_job_instance_id = NULL
WHERE related_job_instance_id IN (
    SELECT id FROM p2_cleanup_job_instance_ids
  );

-- job_instance_dedup_key.first_instance_id 不是运行时关联键，必须趁实例仍在时用完整
-- (tenant_id, dedup_key, run_attempt) 回收本轮 ledger。该删除只覆盖 temp 表解析出的 run_id。
DELETE FROM batch.job_instance_dedup_key ledger
USING batch.job_instance instance
WHERE instance.id IN (SELECT id FROM p2_cleanup_job_instance_ids)
  AND ledger.tenant_id = instance.tenant_id
  AND ledger.dedup_key = instance.dedup_key
  AND ledger.run_attempt = instance.run_attempt;

DELETE FROM batch.job_instance
WHERE id IN (SELECT id FROM p2_cleanup_job_instance_ids);

DELETE FROM batch.trigger_outbox_event event
WHERE event.payload #>> '{launchRequest,params,metadata,runId}' = :'run_id'
   OR EXISTS (
       SELECT 1 FROM batch.trigger_request request
       WHERE request.tenant_id = event.tenant_id AND request.request_id = event.request_id
         AND request.launch_payload #>> '{params,metadata,runId}' = :'run_id'
   );

DELETE FROM batch.trigger_request
WHERE launch_payload #>> '{params,metadata,runId}' = :'run_id';

COMMIT;
-- DANGER: 删除压测 worker 相关控制面数据。仅限专用本地压测库；执行前核对连接目标并保留 RUN_ID 范围。
