-- DQ 检查将 JobInstance 标识保留为弱历史血缘。热表外键会阻止
-- JobInstance 归档，因此这里串行化关联和标识变更，但不阻止删除。

CREATE OR REPLACE FUNCTION batch.check_data_quality_check_job_instance_reference()
RETURNS TRIGGER AS $$
DECLARE
    matching_instance_count BIGINT;
BEGIN
    SELECT count(*)
      INTO matching_instance_count
      FROM (
          SELECT job_instance.id
            FROM batch.job_instance job_instance
           WHERE job_instance.tenant_id = NEW.tenant_id
             AND job_instance.id = NEW.job_instance_id
           FOR KEY SHARE
      ) matching_instances;

    IF matching_instance_count <> 1 THEN
        RAISE EXCEPTION USING
            ERRCODE = '23503',
            CONSTRAINT = 'fk_dq_check_job_instance_reference',
            MESSAGE = 'data_quality_check must reference exactly one same-tenant JobInstance';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_dq_check_job_instance_reference ON batch.data_quality_check;
CREATE TRIGGER trg_dq_check_job_instance_reference
    BEFORE INSERT OR UPDATE OF tenant_id, job_instance_id
    ON batch.data_quality_check
    FOR EACH ROW
    EXECUTE FUNCTION batch.check_data_quality_check_job_instance_reference();

COMMENT ON FUNCTION batch.check_data_quality_check_job_instance_reference() IS
    '将 DQ 历史 JobInstance 关联与父记录删除串行化，并要求存在且仅存在一条同租户活跃父记录。';
COMMENT ON TRIGGER trg_dq_check_job_instance_reference ON batch.data_quality_check IS
    '关联时必须存在唯一的同租户 JobInstance；之后仍允许归档父记录。';

CREATE OR REPLACE FUNCTION batch.protect_job_instance_workflow_node_reference()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE'
       OR OLD.id IS DISTINCT FROM NEW.id
       OR OLD.tenant_id IS DISTINCT FROM NEW.tenant_id
       OR OLD.biz_date IS DISTINCT FROM NEW.biz_date THEN
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

    IF TG_OP <> 'DELETE'
       AND (OLD.id IS DISTINCT FROM NEW.id
            OR OLD.tenant_id IS DISTINCT FROM NEW.tenant_id
            OR OLD.biz_date IS DISTINCT FROM NEW.biz_date)
       AND EXISTS (
            SELECT 1
              FROM batch.workflow_run workflow_run
             WHERE workflow_run.related_job_instance_id = OLD.id
               AND workflow_run.tenant_id = OLD.tenant_id
        ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23503',
            CONSTRAINT = 'fk_workflow_run_related_job_instance_reference',
            MESSAGE = 'job_instance identity cannot change while referenced by workflow_run history';
    END IF;

    IF TG_OP <> 'DELETE'
       AND (OLD.id IS DISTINCT FROM NEW.id
            OR OLD.tenant_id IS DISTINCT FROM NEW.tenant_id
            OR OLD.biz_date IS DISTINCT FROM NEW.biz_date)
       AND EXISTS (
            SELECT 1
              FROM batch.trigger_request trigger_request
             WHERE trigger_request.related_job_instance_id = OLD.id
               AND trigger_request.tenant_id = OLD.tenant_id
        ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23503',
            CONSTRAINT = 'fk_trigger_request_related_job_instance_reference',
            MESSAGE = 'job_instance identity cannot change while referenced by trigger_request history';
    END IF;

    IF TG_OP <> 'DELETE'
       AND (OLD.id IS DISTINCT FROM NEW.id
            OR OLD.tenant_id IS DISTINCT FROM NEW.tenant_id
            OR OLD.biz_date IS DISTINCT FROM NEW.biz_date)
       AND EXISTS (
            SELECT 1
              FROM batch.data_quality_check dq_check
             WHERE dq_check.job_instance_id = OLD.id
               AND dq_check.tenant_id = OLD.tenant_id
        ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23503',
            CONSTRAINT = 'fk_dq_check_job_instance_reference',
            MESSAGE = 'job_instance identity cannot change while referenced by data_quality_check history';
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION batch.protect_job_instance_workflow_node_reference() IS
    '保护活跃 WorkflowNodeRun 引用以及 WorkflowRun、TriggerRequest 和 DQ 的弱 lineage 身份，同时允许删除历史弱关联。';
COMMENT ON TRIGGER trg_job_instance_workflow_node_reference_guard ON batch.job_instance IS
    '当活跃 Workflow 节点仍引用 JobInstance 时，拒绝删除或变更其键；当 WorkflowRun、TriggerRequest 或 DQ 历史记录引用其身份时，也拒绝身份变更。';
