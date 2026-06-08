SELECT COUNT(*)
FROM :"schema".job_task jt
JOIN :"schema".worker_registry wr
     ON wr.worker_code = jt.assigned_worker_code
    AND wr.tenant_id  = jt.tenant_id
WHERE wr.status = 'DECOMMISSIONED'
  AND jt.task_status IN ('RUNNING','READY','CREATED');
