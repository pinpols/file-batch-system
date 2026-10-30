-- 使用清理前保存的根 ID 清单独立核对；不能在请求已删除后靠请求号前缀猜测归属。
SELECT 'trigger_request' AS resource, count(*) AS remaining
FROM batch.trigger_request
WHERE tenant_id = :'tenant_id'
  AND launch_payload #>> '{params,metadata,runId}' = :'run_id'
UNION ALL
SELECT 'workflow_run', count(*)
FROM batch.workflow_run
WHERE tenant_id = :'tenant_id'
  AND id = ANY(string_to_array(NULLIF(:'workflow_run_ids', ''), ',')::bigint[])
UNION ALL
SELECT 'job_instance', count(*)
FROM batch.job_instance
WHERE tenant_id = :'tenant_id'
  AND coalesce(
      params_snapshot #>> '{requestParams,metadata,runId}',
      params_snapshot #>> '{effectiveParams,metadata,runId}',
      params_snapshot #>> '{metadata,runId}',
      params_snapshot #>> '{runId}'
  ) = :'run_id'
UNION ALL
SELECT 'orphan_workflow_node_run', count(*)
FROM batch.workflow_node_run node
LEFT JOIN batch.workflow_run workflow ON workflow.id = node.workflow_run_id
WHERE workflow.id IS NULL;
