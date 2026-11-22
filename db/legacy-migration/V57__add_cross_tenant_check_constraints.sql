-- #9-2: workflow_run 与 job_instance 的 tenant_id 一致性约束。
-- 由于 PostgreSQL 不支持跨表 CHECK 约束，使用触发器函数实现。

CREATE OR REPLACE FUNCTION batch.check_workflow_run_tenant_consistency()
RETURNS TRIGGER AS $$
BEGIN
  IF NEW.related_job_instance_id IS NOT NULL THEN
    IF NOT EXISTS (
      SELECT 1 FROM batch.job_instance
      WHERE id = NEW.related_job_instance_id AND tenant_id = NEW.tenant_id
    ) THEN
      RAISE EXCEPTION 'workflow_run.tenant_id (%) does not match job_instance.tenant_id for related_job_instance_id (%)',
        NEW.tenant_id, NEW.related_job_instance_id;
    END IF;
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_workflow_run_tenant_check ON batch.workflow_run;
CREATE TRIGGER trg_workflow_run_tenant_check
  BEFORE INSERT OR UPDATE OF related_job_instance_id, tenant_id ON batch.workflow_run
  FOR EACH ROW
  EXECUTE FUNCTION batch.check_workflow_run_tenant_consistency();
