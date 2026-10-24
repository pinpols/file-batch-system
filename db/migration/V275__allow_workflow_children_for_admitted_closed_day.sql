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
               run.biz_date,
               run.run_status IN ('CREATED', 'RUNNING', 'PAUSED')
          INTO target_calendar_code, parent_workflow_biz_date, parent_workflow_admitted
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

    PERFORM batch.acquire_batch_day_settlement_fence(
        NEW.tenant_id, target_calendar_code, NEW.biz_date);

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

    RETURN NEW;
END;
$function$;

COMMENT ON FUNCTION batch.guard_trigger_request_batch_day_admission() IS
    'Fences Workflow trigger admission with batch-day settlement; child Job requests must retain their parent WorkflowRun business date and inherit closed-day admission only from an active parent, while new roots require CATCH_UP or RERUN.';
