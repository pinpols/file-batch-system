-- job_instance is partitioned, so PostgreSQL cannot enforce a composite FK from
-- workflow_node_run. Resolve the node's tenant through its owning WorkflowRun.
CREATE OR REPLACE FUNCTION batch.check_workflow_node_run_job_instance_tenant()
RETURNS TRIGGER AS $$
DECLARE
    owning_tenant_id VARCHAR(64);
BEGIN
    IF NEW.job_instance_id IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT workflow_run.tenant_id
      INTO owning_tenant_id
      FROM batch.workflow_run workflow_run
     WHERE workflow_run.id = NEW.workflow_run_id;

    IF owning_tenant_id IS NULL OR NOT EXISTS (
        SELECT 1
          FROM batch.job_instance job_instance
         WHERE job_instance.tenant_id = owning_tenant_id
           AND job_instance.id = NEW.job_instance_id
    ) THEN
        RAISE EXCEPTION 'workflow_node_run job_instance_id (%) must belong to workflow_run tenant (%)',
            NEW.job_instance_id, owning_tenant_id
            USING ERRCODE = '23503';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_workflow_node_run_job_instance_tenant_check
    ON batch.workflow_node_run;
CREATE TRIGGER trg_workflow_node_run_job_instance_tenant_check
    BEFORE INSERT OR UPDATE OF workflow_run_id, job_instance_id
    ON batch.workflow_node_run
    FOR EACH ROW
    EXECUTE FUNCTION batch.check_workflow_node_run_job_instance_tenant();
