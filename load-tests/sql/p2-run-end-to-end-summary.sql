-- 根请求端到端统计：Workflow 入口按根请求关联 workflow_run，子 JOB 与直接 Job 入口分行计数。
-- 必需 psql 变量：run_id、tenant_id、biz_date、biz_date_cardinality；范围口径与 control-run-terminal-counts 一致。
-- 只统计请求 ID 以 run_id 开头的根请求；派发 JOB 节点生成的 child-* 请求由 parent_workflow_run_id 单独汇总。
-- sample_status 非 ok 表示该行不能用于容量结论：根请求存在但没有匹配到 workflow_run，或没有任何完成样本。
WITH root_requests AS (
  SELECT
    request.id,
    request.tenant_id,
    request.created_at AS requested_at,
    request.workflow_definition_id
  FROM batch.trigger_request request
  WHERE request.request_id LIKE :'run_id' || '%'
    AND request.tenant_id = :'tenant_id'
    AND request.biz_date >= :'biz_date'::date
    AND request.biz_date < :'biz_date'::date + :'biz_date_cardinality'::integer
), workflow_roots AS (
  SELECT
    root.id,
    root.requested_at,
    workflow.created_at AS admitted_at,
    workflow.finished_at,
    CASE
      WHEN workflow.id IS NULL THEN NULL
      WHEN workflow.run_status IN ('SUCCESS', 'SUCCESS_DRY_RUN') THEN 'success'
      WHEN workflow.run_status IN ('FAILED', 'FAILED_DRY_RUN', 'TERMINATED') THEN 'failed'
      ELSE 'non_terminal'
    END AS outcome
  FROM root_requests root
  LEFT JOIN batch.workflow_run workflow
    ON workflow.trigger_request_id = root.id
   AND workflow.tenant_id = root.tenant_id
  WHERE root.workflow_definition_id IS NOT NULL
), workflow_run_ids AS (
  SELECT workflow.id
  FROM root_requests root
  JOIN batch.workflow_run workflow
    ON workflow.trigger_request_id = root.id
   AND workflow.tenant_id = root.tenant_id
), child_jobs AS (
  SELECT
    'child_job' AS entry,
    request.id AS source_id,
    request.created_at AS requested_at,
    instance.created_at AS admitted_at,
    instance.finished_at,
    CASE
      WHEN instance.instance_status IN ('SUCCESS') THEN 'success'
      WHEN instance.instance_status IN
        ('FAILED', 'PARTIAL_FAILED', 'CANCELLED', 'TERMINATED', 'REJECTED') THEN 'failed'
      ELSE 'non_terminal'
    END AS outcome
  FROM batch.trigger_request request
  JOIN batch.job_instance instance
    ON instance.trigger_request_id = request.id
   AND instance.tenant_id = request.tenant_id
  WHERE request.parent_workflow_run_id IN (SELECT id FROM workflow_run_ids)
), direct_job_roots AS (
  SELECT
    'direct_job_root' AS entry,
    request.id AS source_id,
    request.created_at AS requested_at,
    instance.created_at AS admitted_at,
    instance.finished_at,
    CASE
      WHEN instance.instance_status IN ('SUCCESS') THEN 'success'
      WHEN instance.instance_status IN
        ('FAILED', 'PARTIAL_FAILED', 'CANCELLED', 'TERMINATED', 'REJECTED') THEN 'failed'
      ELSE 'non_terminal'
    END AS outcome
  FROM root_requests root
  JOIN batch.trigger_request request
    ON request.id = root.id
  JOIN batch.job_instance instance
    ON instance.trigger_request_id = request.id
   AND instance.tenant_id = request.tenant_id
  WHERE root.workflow_definition_id IS NULL
    AND NOT EXISTS (
      SELECT 1
      FROM batch.workflow_run workflow
      WHERE workflow.trigger_request_id = request.id
        AND workflow.tenant_id = request.tenant_id
    )
), scoped AS (
  SELECT
    'workflow_root' AS entry,
    root.id AS source_id,
    root.requested_at,
    root.admitted_at,
    root.finished_at,
    root.outcome
  FROM workflow_roots root
  UNION ALL
  SELECT entry, source_id, requested_at, admitted_at, finished_at, outcome
  FROM child_jobs
  UNION ALL
  SELECT entry, source_id, requested_at, admitted_at, finished_at, outcome
  FROM direct_job_roots
)
SELECT
  entry,
  count(*) AS requests,
  count(admitted_at) AS samples,
  count(*) FILTER (WHERE outcome = 'success') AS success,
  count(*) FILTER (WHERE outcome = 'failed') AS failed,
  count(*) FILTER (WHERE outcome = 'non_terminal') AS non_terminal,
  round(avg(extract(epoch FROM (admitted_at - requested_at)))::numeric, 3)
    AS avg_queue_seconds,
  round(percentile_cont(0.95) WITHIN GROUP (
    ORDER BY extract(epoch FROM (admitted_at - requested_at)))::numeric, 3)
    AS p95_queue_seconds,
  round(avg(extract(epoch FROM (finished_at - requested_at)))
    FILTER (WHERE finished_at IS NOT NULL)::numeric, 3)
    AS avg_end_to_end_seconds,
  round(percentile_cont(0.95) WITHIN GROUP (
    ORDER BY extract(epoch FROM (finished_at - requested_at)))
    FILTER (WHERE finished_at IS NOT NULL)::numeric, 3)
    AS p95_end_to_end_seconds,
  round(extract(epoch FROM (
    max(finished_at) FILTER (WHERE finished_at IS NOT NULL)
    - min(requested_at) FILTER (WHERE finished_at IS NOT NULL)))::numeric, 3)
    AS completion_window_seconds,
  round((count(finished_at) / nullif(extract(epoch FROM (
    max(finished_at) FILTER (WHERE finished_at IS NOT NULL)
    - min(requested_at) FILTER (WHERE finished_at IS NOT NULL))), 0))::numeric, 3)
    AS completed_per_second,
  CASE
    WHEN count(*) = 0 THEN 'EMPTY'
    WHEN count(admitted_at) = 0 THEN 'INVALID: no matched entry'
    WHEN count(finished_at) = 0 THEN 'INVALID: no completed sample'
    ELSE 'ok'
  END AS sample_status
FROM scoped
GROUP BY entry
ORDER BY CASE entry
  WHEN 'workflow_root' THEN 1
  WHEN 'child_job' THEN 2
  ELSE 3
END;
