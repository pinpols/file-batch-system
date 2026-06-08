SELECT COUNT(*)
FROM :"schema".task_assignment ta
JOIN :"schema".worker_registry wr
     ON wr.worker_code = ta.worker_code
    AND wr.tenant_id  = ta.tenant_id
WHERE wr.status = 'DECOMMISSIONED'
  AND ta.assignment_status IN ('CLAIMED','RUNNING');
