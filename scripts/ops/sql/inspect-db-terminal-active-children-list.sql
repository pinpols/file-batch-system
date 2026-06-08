SELECT ji.tenant_id, ji.id, ji.job_code, ji.instance_status, ji.updated_at
FROM :"schema".job_instance ji
WHERE ji.instance_status IN ('SUCCESS','FAILED','PARTIAL_FAILED','CANCELLED','TERMINATED')
  AND (
    EXISTS (
      SELECT 1 FROM :"schema".job_partition p
      WHERE p.tenant_id = ji.tenant_id
        AND p.job_instance_id = ji.id
        AND p.partition_status IN ('CREATED','WAITING','READY','RUNNING','RETRYING')
    )
    OR EXISTS (
      SELECT 1 FROM :"schema".job_task t
      WHERE t.tenant_id = ji.tenant_id
        AND t.job_instance_id = ji.id
        AND t.task_status IN ('CREATED','READY','RUNNING')
    )
  )
ORDER BY ji.updated_at ASC
LIMIT 10;
