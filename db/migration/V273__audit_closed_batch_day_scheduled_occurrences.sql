-- 记录已关闭批量日拒绝计划 occurrence 的原因，供后续审计和恢复判断。

ALTER TABLE batch.scheduled_occurrence
    ADD COLUMN last_rejection_reason VARCHAR(512);

ALTER TABLE batch.scheduled_occurrence
    ADD CONSTRAINT ck_scheduled_occurrence_closed_rejection CHECK (
        occurrence_status <> 'CLOSED_DAY_REJECTED'
        OR (last_rejection_reason IS NOT NULL AND request_id IS NULL)
    ) NOT VALID;

ALTER TABLE batch.scheduled_occurrence
    VALIDATE CONSTRAINT ck_scheduled_occurrence_closed_rejection;

COMMENT ON COLUMN batch.scheduled_occurrence.last_rejection_reason IS
    '观测到的定时触发被拒绝的原因；后续显式补跑接受同一触发身份时仍保留此原因。';

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
            USING ERRCODE = 'BDA01';
    END IF;

    RETURN NEW;
END;
$function$;

COMMENT ON FUNCTION batch.guard_trigger_request_batch_day_admission() IS
    '将 Workflow 触发准入与批次日结算串行化；SQLSTATE BDA01 表示业务日已关闭，便于审计对应 fire，同时不会受理该请求。';
