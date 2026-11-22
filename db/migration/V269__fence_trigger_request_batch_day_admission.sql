-- 在写入触发请求时校验批量日状态，避免结算与新启动并发穿越。

CREATE OR REPLACE FUNCTION batch.guard_trigger_request_batch_day_admission()
RETURNS TRIGGER
LANGUAGE plpgsql
VOLATILE
AS $function$
DECLARE
    target_calendar_code VARCHAR(128);
    current_day_status VARCHAR(32);
BEGIN
    IF NEW.biz_date IS NULL THEN
        RAISE EXCEPTION 'trigger request requires an explicit business date'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.parent_workflow_run_id IS NOT NULL THEN
        SELECT workflow.calendar_code
          INTO target_calendar_code
          FROM batch.workflow_run run
          JOIN batch.workflow_definition workflow
            ON workflow.tenant_id = run.tenant_id
           AND workflow.id = run.workflow_definition_id
         WHERE run.tenant_id = NEW.tenant_id
           AND run.id = NEW.parent_workflow_run_id;
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
        -- 缺少受管单作业 Workflow 时，必须在终态补扫记录中保留可见的
        -- 拒绝结果。由于没有可执行目标，不会进入批次日准入围栏。
        RETURN NEW;
    END IF;

    IF target_calendar_code IS NULL THEN
        RAISE EXCEPTION 'trigger request requires a valid Workflow identity to establish batch-day identity (tenant_id=%, workflow_definition_id=%, parent_workflow_run_id=%)',
            NEW.tenant_id, NEW.workflow_definition_id, NEW.parent_workflow_run_id
            USING ERRCODE = '23514';
    END IF;

    PERFORM batch.acquire_batch_day_settlement_fence(
        NEW.tenant_id, target_calendar_code, NEW.biz_date);

    SELECT day.day_status
      INTO current_day_status
      FROM batch.batch_day_instance day
     WHERE day.tenant_id = NEW.tenant_id
       AND day.calendar_code = target_calendar_code
       AND day.biz_date = NEW.biz_date;

    IF current_day_status IN ('SETTLED', 'FAILED', 'SKIPPED', 'MANUAL_RELEASED')
       AND NEW.trigger_type NOT IN ('CATCH_UP', 'RERUN') THEN
        RAISE EXCEPTION 'batch day %/%/% is closed; an explicit CATCH_UP or RERUN request is required',
            NEW.tenant_id, target_calendar_code, NEW.biz_date
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$function$;

CREATE TRIGGER trg_trigger_request_batch_day_admission
    BEFORE INSERT ON batch.trigger_request
    FOR EACH ROW
    EXECUTE FUNCTION batch.guard_trigger_request_batch_day_admission();

COMMENT ON FUNCTION batch.guard_trigger_request_batch_day_admission() IS
    '将 Workflow 触发准入与批次日结算串行化；围栏外仅允许保留已终结且未关联的 CATCH_UP 拒绝记录。';
