-- 仅在满足以下条件时保留历史 WorkflowRun → JobInstance 弱引用：
-- 必须存在同租户的父记录。完成关联后，仍允许归档或删除父记录。

CREATE OR REPLACE FUNCTION batch.check_workflow_run_tenant_consistency()
RETURNS TRIGGER AS $$
BEGIN
  IF NEW.related_job_instance_id IS NOT NULL THEN
    PERFORM 1
      FROM batch.job_instance
     WHERE id = NEW.related_job_instance_id
       AND tenant_id = NEW.tenant_id
     FOR KEY SHARE;

    IF NOT FOUND THEN
      RAISE EXCEPTION USING
        ERRCODE = '23503',
        CONSTRAINT = 'fk_workflow_run_related_job_instance_reference',
        MESSAGE = 'workflow_run related_job_instance_id must reference an existing JobInstance in the same tenant';
    END IF;
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- TriggerRequest 将已关联实例 ID 保留为弱历史血缘引用：父记录可以在
-- 关联提交后归档，但关联操作必须与父记录删除或重设标识串行化。

CREATE OR REPLACE FUNCTION batch.check_trigger_request_job_instance_reference()
RETURNS TRIGGER AS $$
BEGIN
  IF NEW.related_job_instance_id IS NOT NULL THEN
    PERFORM 1
      FROM batch.job_instance
     WHERE id = NEW.related_job_instance_id
       AND tenant_id = NEW.tenant_id
     FOR KEY SHARE;

    IF NOT FOUND THEN
      RAISE EXCEPTION USING
        ERRCODE = '23503',
        CONSTRAINT = 'fk_trigger_request_related_job_instance_reference',
        MESSAGE = 'trigger_request related_job_instance_id must reference an existing JobInstance in the same tenant';
    END IF;
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- TriggerRequest 的父 WorkflowRun 同样是历史弱引用：关联操作会
-- 通过 V286 的批次日准入围栏和 FOR UPDATE 锁进行串行化，归档
-- 在子请求提交后仍可删除父记录。

CREATE OR REPLACE FUNCTION batch.protect_workflow_run_trigger_request_reference()
RETURNS TRIGGER AS $$
BEGIN
    IF (OLD.id IS DISTINCT FROM NEW.id
        OR OLD.tenant_id IS DISTINCT FROM NEW.tenant_id
        OR OLD.biz_date IS DISTINCT FROM NEW.biz_date)
       AND EXISTS (
            SELECT 1
              FROM batch.trigger_request trigger_request
             WHERE trigger_request.parent_workflow_run_id = OLD.id
               AND trigger_request.tenant_id = OLD.tenant_id
        ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23503',
            CONSTRAINT = 'fk_trigger_request_parent_workflow_run_reference',
            MESSAGE = 'WorkflowRun identity cannot change while referenced by TriggerRequest history';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION batch.reject_trigger_request_parent_workflow_relink()
RETURNS TRIGGER AS $$
BEGIN
    IF (OLD.parent_workflow_run_id IS DISTINCT FROM NEW.parent_workflow_run_id
        OR OLD.parent_node_code IS DISTINCT FROM NEW.parent_node_code) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            CONSTRAINT = 'ck_trigger_request_parent_workflow_run_immutable',
            MESSAGE = 'TriggerRequest parent WorkflowRun identity is immutable after creation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- WorkflowRun 将其保留为弱历史血缘：删除 JobInstance
-- 仍被允许，但变更其标识会使血缘关系
-- 变得含糊或指向另一条记录。此处校验与子侧的
-- FOR KEY SHARE 锁串行化，热表 WorkflowNodeRun 引用也由 V305 采用相同做法。

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

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_job_instance_workflow_node_reference_guard
    ON batch.job_instance;
CREATE TRIGGER trg_job_instance_workflow_node_reference_guard
    BEFORE DELETE OR UPDATE OF tenant_id, id, biz_date
    ON batch.job_instance
    FOR EACH ROW
    EXECUTE FUNCTION batch.protect_job_instance_workflow_node_reference();

COMMENT ON FUNCTION batch.protect_job_instance_workflow_node_reference() IS
    '保护活跃 WorkflowNodeRun 引用以及 WorkflowRun/TriggerRequest 的弱 lineage 身份，同时允许删除历史弱关联。';
COMMENT ON TRIGGER trg_job_instance_workflow_node_reference_guard ON batch.job_instance IS
    '当活跃 Workflow 节点仍引用 JobInstance 时，拒绝删除或变更其键；当 WorkflowRun 或 TriggerRequest 历史记录引用其身份时，也拒绝身份变更。';

DROP TRIGGER IF EXISTS trg_trigger_request_job_instance_reference
    ON batch.trigger_request;
CREATE TRIGGER trg_trigger_request_job_instance_reference
    BEFORE INSERT OR UPDATE OF tenant_id, related_job_instance_id
    ON batch.trigger_request
    FOR EACH ROW
    EXECUTE FUNCTION batch.check_trigger_request_job_instance_reference();
COMMENT ON FUNCTION batch.check_trigger_request_job_instance_reference() IS
    '将 TriggerRequest 历史 JobInstance 关联与父记录删除串行化，并在没有热外键的情况下确保同租户父记录存在。';
COMMENT ON TRIGGER trg_trigger_request_job_instance_reference ON batch.trigger_request IS
    'TriggerRequest 关联历史弱引用时必须存在同租户 JobInstance，同时允许之后归档父记录。';

DROP TRIGGER IF EXISTS trg_workflow_run_trigger_request_reference_guard
    ON batch.workflow_run;
CREATE TRIGGER trg_workflow_run_trigger_request_reference_guard
    BEFORE UPDATE OF id, tenant_id, biz_date
    ON batch.workflow_run
    FOR EACH ROW
    EXECUTE FUNCTION batch.protect_workflow_run_trigger_request_reference();
COMMENT ON FUNCTION batch.protect_workflow_run_trigger_request_reference() IS
    '当 WorkflowRun 作为 TriggerRequest 历史记录保留时，禁止变更其键、租户或业务日期，同时允许归档删除。';
COMMENT ON TRIGGER trg_workflow_run_trigger_request_reference_guard ON batch.workflow_run IS
    '保护作为 TriggerRequest 历史父记录的 WorkflowRun 身份，并允许归档时删除弱关联。';

DROP TRIGGER IF EXISTS trg_trigger_request_parent_workflow_immutable
    ON batch.trigger_request;
CREATE TRIGGER trg_trigger_request_parent_workflow_immutable
    BEFORE UPDATE OF parent_workflow_run_id, parent_node_code
    ON batch.trigger_request
    FOR EACH ROW
    EXECUTE FUNCTION batch.reject_trigger_request_parent_workflow_relink();
COMMENT ON FUNCTION batch.reject_trigger_request_parent_workflow_relink() IS
    '创建后保持 TriggerRequest 的父 WorkflowRun/节点快照不可变；初次关联由 V286 批次日准入触发器保护。';
COMMENT ON TRIGGER trg_trigger_request_parent_workflow_immutable ON batch.trigger_request IS
    '拒绝在请求创建后更改或清空历史父 WorkflowRun/节点身份。';
