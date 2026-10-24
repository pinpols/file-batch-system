-- workflow_node_run cannot use a composite FK to the partitioned job_instance
-- table because its reference does not carry the partition key. Complete the
-- V290 tenant check with parent row locking and a parent-side reference guard.
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

    IF owning_tenant_id IS NULL THEN
        RAISE EXCEPTION 'workflow_node_run job_instance_id (%) has no owning workflow run (%)',
            NEW.job_instance_id, NEW.workflow_run_id
            USING ERRCODE = '23503',
                  CONSTRAINT = 'fk_workflow_node_run_job_instance_reference';
    END IF;

    -- Serialize attachment against delete and identity changes on the partitioned parent.
    PERFORM job_instance.id
      FROM batch.job_instance job_instance
     WHERE job_instance.tenant_id = owning_tenant_id
       AND job_instance.id = NEW.job_instance_id
     FOR KEY SHARE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'workflow_node_run job_instance_id (%) must belong to workflow_run tenant (%)',
            NEW.job_instance_id, owning_tenant_id
            USING ERRCODE = '23503',
                  CONSTRAINT = 'fk_workflow_node_run_job_instance_reference';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION batch.protect_job_instance_workflow_node_reference()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE'
       OR OLD.id IS DISTINCT FROM NEW.id
       OR OLD.tenant_id IS DISTINCT FROM NEW.tenant_id THEN
        IF EXISTS (
            SELECT 1
              FROM batch.workflow_node_run node_run
             WHERE node_run.job_instance_id = OLD.id
        ) THEN
            RAISE EXCEPTION 'job_instance (%) is still referenced by a hot workflow_node_run', OLD.id
                USING ERRCODE = '23503',
                      CONSTRAINT = 'fk_workflow_node_run_job_instance_reference';
        END IF;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_job_instance_workflow_node_reference_guard
    ON batch.job_instance;
CREATE TRIGGER trg_job_instance_workflow_node_reference_guard
    BEFORE DELETE OR UPDATE OF tenant_id, id
    ON batch.job_instance
    FOR EACH ROW
    EXECUTE FUNCTION batch.protect_job_instance_workflow_node_reference();

COMMENT ON FUNCTION batch.protect_job_instance_workflow_node_reference() IS
    'Protects the non-partition-key workflow_node_run.job_instance_id reference on hot JobInstance delete or identity change';
COMMENT ON TRIGGER trg_job_instance_workflow_node_reference_guard ON batch.job_instance IS
    'Refuses deleting or re-keying a JobInstance while hot Workflow nodes reference it; V305 counterpart of V290 row-locking tenant guard';
