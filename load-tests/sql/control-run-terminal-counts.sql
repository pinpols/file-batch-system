-- 入口接纳/终态以 WorkflowRun 为准；子 Job 的执行结果另由 success-counts 查询对账。
SELECT count(workflow.id) || '|'
       || count(workflow.id) FILTER (
           WHERE workflow.run_status IN (
               'SUCCESS', 'FAILED', 'TERMINATED', 'SUCCESS_DRY_RUN', 'FAILED_DRY_RUN'
           )
       ) || '|'
       || count(DISTINCT request.id) || '|'
       || count(DISTINCT request.id) FILTER (
           WHERE workflow.id IS NOT NULL
             AND workflow.run_status IN (
                 'SUCCESS', 'FAILED', 'TERMINATED', 'SUCCESS_DRY_RUN', 'FAILED_DRY_RUN'
             )
       )
FROM batch.trigger_request request
LEFT JOIN batch.workflow_run workflow
  ON workflow.tenant_id = request.tenant_id
 AND workflow.trigger_request_id = request.id
 AND workflow.workflow_definition_id = request.workflow_definition_id
 AND workflow.biz_date = request.biz_date
 AND workflow.biz_date >= :'biz_date'::date
 AND workflow.biz_date < :'biz_date'::date + :'biz_date_cardinality'::integer
-- 子 JOB 请求继承相同 metadata，但不能计入根运行接纳数。
WHERE request.workflow_definition_id IS NOT NULL
  AND request.tenant_id = :'tenant_id'
  AND request.launch_payload #>> '{params,metadata,runId}' = :'run_id';
