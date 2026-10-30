-- workflow_node_run 无法对分区表 job_instance 建立复合外键。
-- 该引用未携带分区键。通过父行锁和父侧引用守护补齐
-- V290 的租户校验。

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

    -- 通过父表行锁，将关联写入与分区父表的删除和标识变更串行化。
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
    '当活跃 workflow_node_run.job_instance_id 引用指向 JobInstance 时，阻止删除 JobInstance 或变更其身份。';
COMMENT ON TRIGGER trg_job_instance_workflow_node_reference_guard ON batch.job_instance IS
    '当活跃 Workflow 节点引用 JobInstance 时，拒绝删除或变更其键；这是 V290 行锁租户守卫在 V305 中的对应实现。';
