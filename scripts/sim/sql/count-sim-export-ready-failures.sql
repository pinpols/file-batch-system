SELECT count(*) FROM batch.job_instance instance
WHERE instance.job_code LIKE '%EXPORT%' AND instance.instance_status = 'FAILED'
  AND instance.created_at > now() - interval '2 hours'
  AND NOT EXISTS (
      SELECT 1 FROM batch.job_step_instance step
      WHERE step.tenant_id = instance.tenant_id AND step.job_instance_id = instance.id
        AND step.step_status <> 'READY'
  );
