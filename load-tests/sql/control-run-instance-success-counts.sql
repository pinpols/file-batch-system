-- 本画像使用每个请求一个 JOB 的受管 Workflow；必须同时核对根、节点和真实 Job 实例。
SELECT count(node.id) || '|'
       || count(DISTINCT instance.id) FILTER (
           WHERE node.node_status IN (
               'SUCCESS', 'FAILED', 'SKIPPED', 'TERMINATED', 'SUCCESS_DRY_RUN', 'FAILED_DRY_RUN'
           ) AND instance.instance_status IN (
               'SUCCESS', 'FAILED', 'PARTIAL_FAILED', 'CANCELLED', 'TERMINATED', 'REJECTED',
               'SUCCESS_DRY_RUN', 'FAILED_DRY_RUN'
           )
       ) || '|'
       || count(DISTINCT instance.id) FILTER (
           WHERE workflow.run_status = 'SUCCESS'
             AND node.node_status = 'SUCCESS'
             AND instance.instance_status = 'SUCCESS'
       )
FROM batch.trigger_request request
JOIN batch.workflow_run workflow
  ON workflow.tenant_id = request.tenant_id
 AND workflow.trigger_request_id = request.id
 AND workflow.workflow_definition_id = request.workflow_definition_id
 AND workflow.biz_date = request.biz_date
LEFT JOIN batch.workflow_node_run node
  ON node.workflow_run_id = workflow.id
 AND node.node_type = 'JOB'
LEFT JOIN batch.job_instance instance
  ON instance.tenant_id = workflow.tenant_id
 AND instance.id = node.job_instance_id
 AND instance.biz_date = workflow.biz_date
WHERE request.tenant_id = :'tenant_id'
  AND request.launch_payload #>> '{params,metadata,runId}' = :'run_id';
