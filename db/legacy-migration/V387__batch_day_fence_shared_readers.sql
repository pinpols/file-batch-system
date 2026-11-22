-- 把批次日结算栅栏拆成共享读者与独占写者。
-- 准入、节点派发、运行回报只读取业务日状态，共享同一把 advisory key；
-- 开日、人工状态迁移与结算仍使用独占版本，保证结算提交时没有读者跨越窗口。

CREATE OR REPLACE FUNCTION batch.acquire_batch_day_settlement_fence_shared(
    p_tenant_id VARCHAR,
    p_calendar_code VARCHAR,
    p_biz_date DATE
)
RETURNS BIGINT
LANGUAGE plpgsql
VOLATILE
AS $function$
DECLARE
    lock_key BIGINT;
BEGIN
    IF p_tenant_id IS NULL OR p_calendar_code IS NULL OR p_biz_date IS NULL THEN
        RAISE EXCEPTION 'batch-day settlement fence requires tenant, calendar and business date';
    END IF;

    lock_key := hashtextextended(
        jsonb_build_array(
            p_tenant_id,
            p_calendar_code,
            to_char(p_biz_date, 'YYYY-MM-DD')
        )::text,
        26820261014
    );
    PERFORM pg_advisory_xact_lock_shared(lock_key);
    RETURN lock_key;
END;
$function$;

COMMENT ON FUNCTION batch.acquire_batch_day_settlement_fence_shared(VARCHAR, VARCHAR, DATE) IS
    '批次日结算栅栏的共享读者版本：与独占版共用 key，读者之间不互斥，结算的独占请求会等待读者提交。';

CREATE OR REPLACE FUNCTION batch.guard_trigger_request_batch_day_admission()
RETURNS TRIGGER
LANGUAGE plpgsql
VOLATILE
AS $function$
DECLARE
    target_calendar_code VARCHAR(128);
    current_day_status VARCHAR(32);
    parent_workflow_biz_date DATE;
    parent_workflow_admitted BOOLEAN := false;
BEGIN
    IF NEW.biz_date IS NULL THEN
        RAISE EXCEPTION 'trigger request requires an explicit business date'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.parent_workflow_run_id IS NOT NULL THEN
        SELECT workflow.calendar_code,
               run.biz_date
          INTO target_calendar_code, parent_workflow_biz_date
          FROM batch.workflow_run run
          JOIN batch.workflow_definition workflow
            ON workflow.tenant_id = run.tenant_id
           AND workflow.id = run.workflow_definition_id
         WHERE run.tenant_id = NEW.tenant_id
           AND run.id = NEW.parent_workflow_run_id;

        IF parent_workflow_biz_date IS DISTINCT FROM NEW.biz_date THEN
            RAISE EXCEPTION 'child trigger request business date % does not match parent WorkflowRun business date %',
                NEW.biz_date, parent_workflow_biz_date
                USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.workflow_definition_id IS NOT NULL THEN
        SELECT workflow.calendar_code
          INTO target_calendar_code
          FROM batch.workflow_definition workflow
         WHERE workflow.tenant_id = NEW.tenant_id
           AND workflow.id = NEW.workflow_definition_id;
    END IF;

    IF target_calendar_code IS NULL
       AND NEW.parent_workflow_run_id IS NULL
       AND NEW.workflow_definition_id IS NULL
       AND NEW.job_code IS NOT NULL
       AND NEW.trigger_type = 'CATCH_UP'
       AND NEW.request_status = 'REJECTED' THEN
        RETURN NEW;
    END IF;

    IF target_calendar_code IS NULL THEN
        RAISE EXCEPTION 'trigger request requires a valid Workflow identity to establish batch-day identity (tenant_id=%, workflow_definition_id=%, parent_workflow_run_id=%)',
            NEW.tenant_id, NEW.workflow_definition_id, NEW.parent_workflow_run_id
            USING ERRCODE = '23514';
    END IF;

    PERFORM batch.acquire_batch_day_settlement_fence_shared(
        NEW.tenant_id, target_calendar_code, NEW.biz_date);

    IF NEW.parent_workflow_run_id IS NOT NULL THEN
        SELECT run.run_status IN ('CREATED', 'RUNNING', 'PAUSED')
          INTO parent_workflow_admitted
          FROM batch.workflow_run run
         WHERE run.tenant_id = NEW.tenant_id
           AND run.id = NEW.parent_workflow_run_id
         FOR UPDATE;

        IF NOT FOUND THEN
            RAISE EXCEPTION 'parent WorkflowRun % no longer exists for tenant %',
                NEW.parent_workflow_run_id, NEW.tenant_id
                USING ERRCODE = '23514';
        END IF;

    END IF;

    SELECT day.day_status
      INTO current_day_status
      FROM batch.batch_day_instance day
     WHERE day.tenant_id = NEW.tenant_id
       AND day.calendar_code = target_calendar_code
       AND day.biz_date = NEW.biz_date;

    IF current_day_status IN ('SETTLED', 'FAILED', 'SKIPPED', 'MANUAL_RELEASED')
       AND ((NEW.parent_workflow_run_id IS NOT NULL AND NOT parent_workflow_admitted)
            OR (NEW.parent_workflow_run_id IS NULL
                AND NEW.trigger_type NOT IN ('CATCH_UP', 'RERUN'))) THEN
        RAISE EXCEPTION 'batch day %/%/% is closed; a top-level CATCH_UP/RERUN or active parent WorkflowRun is required',
            NEW.tenant_id, target_calendar_code, NEW.biz_date
            USING ERRCODE = 'BDA01';
    END IF;

    IF NEW.parent_workflow_run_id IS NOT NULL AND NOT parent_workflow_admitted THEN
        RAISE EXCEPTION 'parent WorkflowRun % is terminal; child Job admission requires an active parent',
            NEW.parent_workflow_run_id
            USING ERRCODE = 'BDA02';
    END IF;

    RETURN NEW;
END;
$function$;

COMMENT ON FUNCTION batch.guard_trigger_request_batch_day_admission() IS
    '使用批次日结算围栏的共享读者版本保护 Workflow 触发准入；父 WorkflowRun 终结后必须拒绝子 Job 请求，顶层运行在已关闭业务日仅允许通过 CATCH_UP 或 RERUN 准入。';
