-- V2 创建平台库现行结构。历史结构来源为原迁移链完整执行到 V389。

SET LOCAL search_path TO '';
SET LOCAL check_function_bodies = false;

CREATE SCHEMA archive;

COMMENT ON SCHEMA batch IS '批处理调度平台的业务模式。';

COMMENT ON SCHEMA quartz IS 'Quartz JDBC JobStore 使用的模式。';


CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA batch;

CREATE FUNCTION batch.acquire_batch_day_settlement_fence(p_tenant_id character varying, p_calendar_code character varying, p_biz_date date) RETURNS bigint
    LANGUAGE plpgsql
    AS $$
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
    PERFORM pg_advisory_xact_lock(lock_key);
    RETURN lock_key;
END;
$$;


COMMENT ON FUNCTION batch.acquire_batch_day_settlement_fence(p_tenant_id character varying, p_calendar_code character varying, p_biz_date date) IS '由批次日结算以及相同租户、日历和业务日期的所有工作项/预期项写入方共享的事务级围栏。';


CREATE FUNCTION batch.acquire_batch_day_settlement_fence_shared(p_tenant_id character varying, p_calendar_code character varying, p_biz_date date) RETURNS bigint
    LANGUAGE plpgsql
    AS $$
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
$$;


COMMENT ON FUNCTION batch.acquire_batch_day_settlement_fence_shared(p_tenant_id character varying, p_calendar_code character varying, p_biz_date date) IS '批次日结算栅栏的共享读者版本：与独占版共用 key，读者之间不互斥，结算的独占请求会等待读者提交。';


CREATE FUNCTION batch.bump_tenant_configuration_revision() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'batch', 'pg_temp'
    AS $$
DECLARE
    old_tenant_id TEXT;
    new_tenant_id TEXT;
    old_row JSONB;
    new_row JSONB;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        old_row := to_jsonb(OLD);
        old_tenant_id := old_row ->> 'tenant_id';
    END IF;
    IF TG_OP <> 'DELETE' THEN
        new_row := to_jsonb(NEW);
        new_tenant_id := new_row ->> 'tenant_id';
    END IF;

    IF old_tenant_id IS NOT NULL THEN
        INSERT INTO batch.tenant_configuration_revision (tenant_id, revision, updated_at)
        VALUES (old_tenant_id, 1, clock_timestamp())
        ON CONFLICT (tenant_id) DO UPDATE
        SET revision = batch.tenant_configuration_revision.revision + 1,
            updated_at = clock_timestamp();
    END IF;

    IF new_tenant_id IS NOT NULL AND new_tenant_id IS DISTINCT FROM old_tenant_id THEN
        INSERT INTO batch.tenant_configuration_revision (tenant_id, revision, updated_at)
        VALUES (new_tenant_id, 1, clock_timestamp())
        ON CONFLICT (tenant_id) DO UPDATE
        SET revision = batch.tenant_configuration_revision.revision + 1,
            updated_at = clock_timestamp();
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;


CREATE FUNCTION batch.check_data_quality_check_job_instance_reference() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
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
$$;


COMMENT ON FUNCTION batch.check_data_quality_check_job_instance_reference() IS '将 DQ 历史 JobInstance 关联与父记录删除串行化，并要求存在且仅存在一条同租户活跃父记录。';


CREATE FUNCTION batch.check_trigger_request_job_instance_reference() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
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
$$;


COMMENT ON FUNCTION batch.check_trigger_request_job_instance_reference() IS '将 TriggerRequest 历史 JobInstance 关联与父记录删除串行化，并在没有热外键的情况下确保同租户父记录存在。';


CREATE FUNCTION batch.check_workflow_node_run_job_instance_tenant() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
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
$$;


CREATE FUNCTION batch.check_workflow_run_tenant_consistency() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
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
$$;


CREATE FUNCTION batch.cleanup_missing_resource_tag_parent() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'batch', 'pg_temp'
    AS $_$
DECLARE
    old_tenant_id TEXT;
    old_resource_code TEXT;
    target_still_exists BOOLEAN;
BEGIN
    old_tenant_id := to_jsonb(OLD) ->> 'tenant_id';
    old_resource_code := to_jsonb(OLD) ->> TG_ARGV[1];

    EXECUTE pg_catalog.format(
        'SELECT EXISTS (SELECT 1 FROM batch.%I WHERE tenant_id = $1 AND %I = $2)',
        TG_ARGV[2], TG_ARGV[1])
       INTO target_still_exists
       USING old_tenant_id, old_resource_code;

    IF NOT target_still_exists THEN
        DELETE FROM batch.resource_tag
         WHERE tenant_id = old_tenant_id
           AND resource_type = TG_ARGV[0]
           AND resource_code = old_resource_code;
    END IF;
    RETURN NULL;
END;
$_$;


COMMENT ON FUNCTION batch.cleanup_missing_resource_tag_parent() IS '仅在某租户、类型和编码对应的最后一条资源记录被删除或重命名后，才移除对应 resource_tag。';


CREATE FUNCTION batch.current_tenant_configuration_revision(p_tenant_id text) RETURNS bigint
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'batch', 'pg_temp'
    AS $$
DECLARE
    current_revision BIGINT;
BEGIN
    IF p_tenant_id IS NULL OR btrim(p_tenant_id) = '' THEN
        RAISE EXCEPTION 'tenant id is required';
    END IF;

    INSERT INTO batch.tenant_configuration_revision (tenant_id)
    VALUES (p_tenant_id)
    ON CONFLICT (tenant_id) DO NOTHING;

    SELECT revision
    INTO current_revision
    FROM batch.tenant_configuration_revision
    WHERE tenant_id = p_tenant_id;

    RETURN current_revision;
END;
$$;


CREATE FUNCTION batch.guard_calendar_dependency_acyclic() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    excluded_dependency_id BIGINT;
    cycle_detected         BOOLEAN;
BEGIN
    IF NOT NEW.enabled THEN
        RETURN NEW;
    END IF;

    PERFORM pg_advisory_xact_lock(
        hashtextextended('batch.calendar_dependency:' || NEW.tenant_id, 0));

    IF TG_OP = 'UPDATE' THEN
        excluded_dependency_id := OLD.id;
    ELSE
        excluded_dependency_id := NEW.id;
    END IF;

    WITH RECURSIVE reachable(calendar_code, path) AS (
        SELECT NEW.downstream_code,
               ARRAY[NEW.downstream_code]::VARCHAR[]
        UNION ALL
        SELECT dependency.downstream_code,
               reachable.path || dependency.downstream_code
        FROM reachable
        JOIN batch.calendar_dependency dependency
          ON dependency.tenant_id = NEW.tenant_id
         AND dependency.upstream_code = reachable.calendar_code
         AND dependency.enabled
         AND dependency.id <> excluded_dependency_id
        WHERE NOT dependency.downstream_code = ANY(reachable.path)
    )
    SELECT EXISTS (
        SELECT 1
        FROM reachable
        WHERE calendar_code = NEW.upstream_code
    )
    INTO cycle_detected;

    IF cycle_detected THEN
        RAISE EXCEPTION 'enabled calendar dependency graph contains a cycle'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'ck_calendar_dependency_acyclic',
                  SCHEMA = 'batch',
                  TABLE = 'calendar_dependency';
    END IF;

    RETURN NEW;
END;
$$;


COMMENT ON FUNCTION batch.guard_calendar_dependency_acyclic() IS '按租户串行化启用的日历依赖写入，并拒绝形成依赖环';


CREATE FUNCTION batch.guard_job_dependency_acyclic() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    excluded_downstream VARCHAR(128);
    excluded_upstream   VARCHAR(128);
    cycle_detected      BOOLEAN;
BEGIN
    PERFORM pg_advisory_xact_lock(
        hashtextextended('batch.job_dependency:' || NEW.tenant_id, 0));

    IF TG_OP = 'UPDATE' THEN
        excluded_downstream := OLD.downstream_job_code;
        excluded_upstream := OLD.upstream_job_code;
    END IF;

    WITH RECURSIVE reachable(job_code, path) AS (
        SELECT NEW.upstream_job_code,
               ARRAY[NEW.upstream_job_code]::VARCHAR[]
        UNION ALL
        SELECT dependency.upstream_job_code,
               reachable.path || dependency.upstream_job_code
        FROM reachable
        JOIN batch.job_dependency dependency
          ON dependency.tenant_id = NEW.tenant_id
         AND dependency.downstream_job_code = reachable.job_code
        WHERE (excluded_downstream IS NULL
               OR dependency.downstream_job_code <> excluded_downstream
               OR dependency.upstream_job_code <> excluded_upstream)
          AND NOT dependency.upstream_job_code = ANY(reachable.path)
    )
    SELECT EXISTS (
        SELECT 1
        FROM reachable
        WHERE job_code = NEW.downstream_job_code
    )
    INTO cycle_detected;

    IF cycle_detected THEN
        RAISE EXCEPTION 'job dependency graph contains a cycle'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'ck_job_dependency_acyclic',
                  SCHEMA = 'batch',
                  TABLE = 'job_dependency';
    END IF;

    RETURN NEW;
END;
$$;


COMMENT ON FUNCTION batch.guard_job_dependency_acyclic() IS '按租户串行化依赖图写入，并拒绝会形成环的边，包括直接 SQL 和旧式同步写入。';


CREATE FUNCTION batch.guard_trigger_request_batch_day_admission() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
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
$$;


COMMENT ON FUNCTION batch.guard_trigger_request_batch_day_admission() IS '使用批次日结算围栏的共享读者版本保护 Workflow 触发准入；父 WorkflowRun 终结后必须拒绝子 Job 请求，顶层运行在已关闭业务日仅允许通过 CATCH_UP 或 RERUN 准入。';


CREATE FUNCTION batch.lock_resource_tag_identity(p_tenant_id text, p_resource_type text, p_resource_code text) RETURNS bigint
    LANGUAGE plpgsql
    SET search_path TO 'pg_catalog', 'batch', 'pg_temp'
    AS $$
DECLARE
    lock_key BIGINT;
BEGIN
    IF p_tenant_id IS NULL OR p_resource_type IS NULL OR p_resource_code IS NULL THEN
        RAISE EXCEPTION 'resource tag identity must be complete'
            USING ERRCODE = '22023';
    END IF;

    lock_key := batch.resource_tag_identity_lock_key(
        p_tenant_id, p_resource_type, p_resource_code);
    PERFORM pg_catalog.pg_advisory_xact_lock(lock_key);
    RETURN lock_key;
END;
$$;


COMMENT ON FUNCTION batch.lock_resource_tag_identity(p_tenant_id text, p_resource_type text, p_resource_code text) IS '将多态 resource_tag 写入与父资源身份生命周期变更串行化。';


CREATE FUNCTION batch.lock_resource_tag_parent_identity() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'pg_catalog', 'batch', 'pg_temp'
    AS $$
DECLARE
    old_tenant_id TEXT;
    new_tenant_id TEXT;
    old_resource_code TEXT;
    new_resource_code TEXT;
    old_lock_key BIGINT;
    new_lock_key BIGINT;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        old_tenant_id := to_jsonb(OLD) ->> 'tenant_id';
        old_resource_code := to_jsonb(OLD) ->> TG_ARGV[1];
    END IF;
    IF TG_OP <> 'DELETE' THEN
        new_tenant_id := to_jsonb(NEW) ->> 'tenant_id';
        new_resource_code := to_jsonb(NEW) ->> TG_ARGV[1];
    END IF;

    IF TG_OP = 'UPDATE' THEN
        IF (old_tenant_id, old_resource_code)
           IS NOT DISTINCT FROM (new_tenant_id, new_resource_code) THEN
            RETURN NEW;
        END IF;
        old_lock_key := batch.resource_tag_identity_lock_key(
            old_tenant_id, TG_ARGV[0], old_resource_code);
        new_lock_key := batch.resource_tag_identity_lock_key(
            new_tenant_id, TG_ARGV[0], new_resource_code);
        PERFORM pg_catalog.pg_advisory_xact_lock(LEAST(old_lock_key, new_lock_key));
        IF old_lock_key <> new_lock_key THEN
            PERFORM pg_catalog.pg_advisory_xact_lock(GREATEST(old_lock_key, new_lock_key));
        END IF;
        RETURN NEW;
    END IF;

    IF TG_OP = 'DELETE' THEN
        PERFORM batch.lock_resource_tag_identity(
            old_tenant_id, TG_ARGV[0], old_resource_code);
        RETURN OLD;
    END IF;

    PERFORM batch.lock_resource_tag_identity(
        new_tenant_id, TG_ARGV[0], new_resource_code);
    RETURN NEW;
END;
$$;


CREATE FUNCTION batch.lock_tenant_configuration_revision(p_tenant_id text) RETURNS bigint
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'batch', 'pg_temp'
    AS $$
DECLARE
    current_revision BIGINT;
BEGIN
    IF p_tenant_id IS NULL OR btrim(p_tenant_id) = '' THEN
        RAISE EXCEPTION 'tenant id is required';
    END IF;

    INSERT INTO batch.tenant_configuration_revision (tenant_id)
    VALUES (p_tenant_id)
    ON CONFLICT (tenant_id) DO NOTHING;

    SELECT revision
    INTO current_revision
    FROM batch.tenant_configuration_revision
    WHERE tenant_id = p_tenant_id
    FOR UPDATE;

    RETURN current_revision;
END;
$$;


CREATE FUNCTION batch.protect_job_instance_workflow_node_reference() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
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
$$;


COMMENT ON FUNCTION batch.protect_job_instance_workflow_node_reference() IS '保护活跃 WorkflowNodeRun 引用以及 WorkflowRun、TriggerRequest 和 DQ 的弱 lineage 身份，同时允许删除历史弱关联。';


CREATE FUNCTION batch.protect_workflow_run_trigger_request_reference() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
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
$$;


COMMENT ON FUNCTION batch.protect_workflow_run_trigger_request_reference() IS '当 WorkflowRun 作为 TriggerRequest 历史记录保留时，禁止变更其键、租户或业务日期，同时允许归档删除。';


CREATE FUNCTION batch.record_config_cache_invalidation() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'batch'
    AS $$
DECLARE
    old_tenant TEXT;
    new_tenant TEXT;
    old_code TEXT;
    new_code TEXT;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        old_tenant := to_jsonb(OLD) ->> 'tenant_id';
        old_code := CASE WHEN TG_ARGV[1] = '' THEN 'enabled-first' ELSE to_jsonb(OLD) ->> TG_ARGV[1] END;
        INSERT INTO batch.config_cache_invalidation_outbox (tenant_id, config_type, config_code)
        VALUES (old_tenant, TG_ARGV[0], old_code);
    END IF;
    IF TG_OP <> 'DELETE' THEN
        new_tenant := to_jsonb(NEW) ->> 'tenant_id';
        new_code := CASE WHEN TG_ARGV[1] = '' THEN 'enabled-first' ELSE to_jsonb(NEW) ->> TG_ARGV[1] END;
        IF TG_OP = 'INSERT' OR (new_tenant, new_code) IS DISTINCT FROM (old_tenant, old_code) THEN
            INSERT INTO batch.config_cache_invalidation_outbox (tenant_id, config_type, config_code)
            VALUES (new_tenant, TG_ARGV[0], new_code);
        END IF;
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;


CREATE FUNCTION batch.record_scheduled_occurrence_supersession() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'batch', 'pg_temp'
    AS $$
BEGIN
    IF OLD.occurrence_status IS DISTINCT FROM NEW.occurrence_status
       AND (OLD.occurrence_status = 'SUPERSEDED' OR NEW.occurrence_status = 'SUPERSEDED') THEN
        INSERT INTO batch.scheduled_occurrence_supersession_history (
            tenant_id,
            workflow_definition_id,
            scheduled_fire_time,
            from_status,
            to_status,
            schedule_version,
            calendar_code,
            biz_date,
            request_id
        ) VALUES (
            NEW.tenant_id,
            NEW.workflow_definition_id,
            NEW.scheduled_fire_time,
            OLD.occurrence_status,
            NEW.occurrence_status,
            NEW.schedule_version,
            NEW.calendar_code,
            NEW.biz_date,
            NEW.request_id
        );
    END IF;
    RETURN NEW;
END;
$$;


CREATE FUNCTION batch.reject_trigger_request_parent_workflow_relink() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
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
$$;


COMMENT ON FUNCTION batch.reject_trigger_request_parent_workflow_relink() IS '创建后保持 TriggerRequest 的父 WorkflowRun/节点快照不可变；初次关联由 V286 批次日准入触发器保护。';


CREATE FUNCTION batch.resource_tag_identity_lock_key(p_tenant_id text, p_resource_type text, p_resource_code text) RETURNS bigint
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
    AS $$
    SELECT pg_catalog.hashtextextended(
        p_tenant_id || chr(31) || p_resource_type || chr(31) || p_resource_code,
        0
    )
$$;


CREATE FUNCTION batch.sync_legacy_job_dependency() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    DELETE FROM batch.job_dependency
    WHERE tenant_id = NEW.tenant_id
      AND downstream_job_code = NEW.job_code;

    IF nullif(btrim(NEW.depends_on_job_code), '') IS NOT NULL THEN
        INSERT INTO batch.job_dependency (
            tenant_id, downstream_job_code, upstream_job_code, dependency_order, readiness_mode
        ) VALUES (
            NEW.tenant_id, NEW.job_code, btrim(NEW.depends_on_job_code), 0, 'EFFECTIVE_RESULT'
        );
    END IF;
    RETURN NEW;
END;
$$;


CREATE FUNCTION batch.validate_resource_tag_target() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'pg_catalog', 'batch', 'pg_temp'
    AS $$
DECLARE
    old_lock_key BIGINT;
    new_lock_key BIGINT;
BEGIN
    new_lock_key := batch.resource_tag_identity_lock_key(
        NEW.tenant_id, NEW.resource_type, NEW.resource_code);

    IF TG_OP = 'UPDATE'
       AND (OLD.tenant_id, OLD.resource_type, OLD.resource_code)
           IS DISTINCT FROM (NEW.tenant_id, NEW.resource_type, NEW.resource_code) THEN
        old_lock_key := batch.resource_tag_identity_lock_key(
            OLD.tenant_id, OLD.resource_type, OLD.resource_code);
        PERFORM pg_catalog.pg_advisory_xact_lock(LEAST(old_lock_key, new_lock_key));
        IF old_lock_key <> new_lock_key THEN
            PERFORM pg_catalog.pg_advisory_xact_lock(GREATEST(old_lock_key, new_lock_key));
        END IF;
    ELSE
        PERFORM pg_catalog.pg_advisory_xact_lock(new_lock_key);
    END IF;

    CASE NEW.resource_type
        WHEN 'JOB' THEN
            PERFORM 1 FROM batch.job_definition
             WHERE tenant_id = NEW.tenant_id AND job_code = NEW.resource_code
             LIMIT 1;
        WHEN 'WORKFLOW' THEN
            PERFORM 1 FROM batch.workflow_definition
             WHERE tenant_id = NEW.tenant_id AND workflow_code = NEW.resource_code
             LIMIT 1;
        WHEN 'FILE_CHANNEL' THEN
            PERFORM 1 FROM batch.file_channel_config
             WHERE tenant_id = NEW.tenant_id AND channel_code = NEW.resource_code
             LIMIT 1;
        WHEN 'FILE_TEMPLATE' THEN
            PERFORM 1 FROM batch.file_template_config
             WHERE tenant_id = NEW.tenant_id AND template_code = NEW.resource_code
             LIMIT 1;
        ELSE
            RAISE EXCEPTION 'unsupported resource tag type: %', NEW.resource_type
                USING ERRCODE = '23514', CONSTRAINT = 'ck_resource_tag_type';
    END CASE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'resource tag target does not exist for tenant/type/code'
            USING ERRCODE = '23503',
                  CONSTRAINT = 'fk_resource_tag_target',
                  SCHEMA = 'batch',
                  TABLE = 'resource_tag';
    END IF;
    RETURN NEW;
END;
$$;


COMMENT ON FUNCTION batch.validate_resource_tag_target() IS '拒绝无法按租户、类型和编码解析到受支持资源的 resource_tag 记录。';


SET default_tablespace = '';

SET default_table_access_method = heap;

CREATE TABLE batch.batch_day_replay_entry (
    id bigint NOT NULL,
    session_id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    job_code character varying(128) NOT NULL,
    source_instance_id bigint,
    rerun_instance_id bigint,
    status character varying(32) NOT NULL,
    failure_reason character varying(1024),
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    result_version_id bigint,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    plan_snapshot jsonb,
    CONSTRAINT ck_replay_entry_candidate_shape CHECK (((source_instance_id IS NOT NULL) OR (result_version_id IS NOT NULL) OR (plan_snapshot IS NOT NULL))),
    CONSTRAINT ck_replay_entry_status CHECK (((status)::text = ANY (ARRAY['PENDING'::character varying, 'RUNNING'::character varying, 'SUCCEEDED'::character varying, 'FAILED'::character varying, 'SKIPPED'::character varying]))),
    CONSTRAINT batch_day_replay_entry_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.batch_day_replay_entry IS '批量日重放会话子项；记录单个 job 的源实例、重跑实例和结果版本。';


COMMENT ON COLUMN batch.batch_day_replay_entry.status IS '批量日重放条目状态。';


COMMENT ON COLUMN batch.batch_day_replay_entry.result_version_id IS '重放命中的结果版本标识。';


COMMENT ON COLUMN batch.batch_day_replay_entry.plan_snapshot IS 'SCHEDULE_PLAN 候选的不可变计划快照；历史实例候选为空。';


CREATE SEQUENCE batch.batch_day_replay_entry_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.batch_day_replay_entry_id_seq OWNED BY batch.batch_day_replay_entry.id;


CREATE TABLE archive.batch_day_replay_entry_archive (
    id bigint DEFAULT nextval('batch.batch_day_replay_entry_id_seq'::regclass) NOT NULL,
    session_id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    job_code character varying(128) NOT NULL,
    source_instance_id bigint,
    rerun_instance_id bigint,
    status character varying(32) NOT NULL,
    failure_reason character varying(1024),
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    result_version_id bigint,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    plan_snapshot jsonb,
    CONSTRAINT ck_replay_entry_status CHECK (((status)::text = ANY (ARRAY['PENDING'::character varying, 'RUNNING'::character varying, 'SUCCEEDED'::character varying, 'FAILED'::character varying, 'SKIPPED'::character varying]))),
    CONSTRAINT batch_day_replay_entry_archive_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE archive.batch_day_replay_entry_archive IS 'V110 新增的 batch.batch_day_replay_entry 归档镜像。';


COMMENT ON COLUMN archive.batch_day_replay_entry_archive.plan_snapshot IS '归档镜像：计划来源候选的不可变快照。';


CREATE TABLE batch.batch_day_replay_session (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    calendar_code character varying(128) NOT NULL,
    biz_date date NOT NULL,
    scope character varying(32) NOT NULL,
    scope_payload jsonb,
    result_policy character varying(64) NOT NULL,
    config_version_policy character varying(64) NOT NULL,
    config_version integer,
    reason character varying(1024) NOT NULL,
    approval_id bigint,
    status character varying(32) NOT NULL,
    total_count integer DEFAULT 0 NOT NULL,
    succeeded_count integer DEFAULT 0 NOT NULL,
    failed_count integer DEFAULT 0 NOT NULL,
    in_flight_count integer DEFAULT 0 NOT NULL,
    requested_by character varying(128) NOT NULL,
    approved_by character varying(128),
    started_at timestamp with time zone,
    completed_at timestamp with time zone,
    trace_id character varying(128),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    execution_mode character varying(24) DEFAULT 'REPLAY'::character varying NOT NULL,
    candidate_source character varying(32) DEFAULT 'EXISTING_INSTANCES'::character varying NOT NULL,
    replay_operation_id bigint,
    CONSTRAINT ck_replay_session_candidate_source CHECK (((candidate_source)::text = ANY (ARRAY['EXISTING_INSTANCES'::character varying, 'SCHEDULE_PLAN'::character varying]))),
    CONSTRAINT ck_replay_session_config_version_policy CHECK (((config_version_policy IS NULL) OR ((config_version_policy)::text = ANY (ARRAY['USE_ORIGINAL_CONFIG'::character varying, 'USE_LATEST_CONFIG'::character varying, 'USE_SPECIFIED_VERSION'::character varying, 'SNAPSHOT_JOB_DEFINITION_VERSION_ON_CREATE'::character varying])))),
    CONSTRAINT ck_replay_session_dry_run_contract CHECK ((((execution_mode)::text <> 'DRY_RUN'::text) OR (((scope)::text <> 'OUTPUTS_ONLY'::text) AND ((result_policy)::text = 'DRY_RUN_ONLY'::text)))),
    CONSTRAINT ck_replay_session_execution_mode CHECK (((execution_mode)::text = ANY (ARRAY['REPLAY'::character varying, 'DRY_RUN'::character varying]))),
    CONSTRAINT ck_replay_session_result_policy CHECK (((result_policy)::text = ANY (ARRAY['CREATE_NEW_VERSION'::character varying, 'KEEP_BOTH'::character varying, 'MANUAL_CONFIRM_EFFECTIVE'::character varying, 'DRY_RUN_ONLY'::character varying]))),
    CONSTRAINT ck_replay_session_scope CHECK (((scope)::text = ANY (ARRAY['ALL'::character varying, 'ALL_FAILED'::character varying, 'SUBSET_JOB_CODES'::character varying, 'OUTPUTS_ONLY'::character varying]))),
    CONSTRAINT ck_replay_session_status CHECK (((status)::text = ANY (ARRAY['PENDING_APPROVAL'::character varying, 'RUNNING'::character varying, 'SUCCEEDED'::character varying, 'PARTIAL_FAILED'::character varying, 'CANCELLED'::character varying]))),
    CONSTRAINT batch_day_replay_session_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.batch_day_replay_session IS '批量日重放会话聚合；跟踪指定日历日的作业重跑范围和结果。';


COMMENT ON COLUMN batch.batch_day_replay_session.scope IS 'ALL: 当日所有 SUCCESS/FAILED; ALL_FAILED: 当日 FAILED/PARTIAL_FAILED; SUBSET_JOB_CODES: scope_payload 列出 jobCode; OUTPUTS_ONLY: promote 历史版本不重跑';


COMMENT ON COLUMN batch.batch_day_replay_session.scope_payload IS 'SUBSET_JOB_CODES 时存 {"jobCodes": [...]}, OUTPUTS_ONLY 时存 {"versionIds": [...]}';


COMMENT ON COLUMN batch.batch_day_replay_session.result_policy IS '同 RerunRequest.resultPolicy: CREATE_NEW_VERSION / KEEP_BOTH / MANUAL_CONFIRM_EFFECTIVE';


COMMENT ON COLUMN batch.batch_day_replay_session.config_version_policy IS '配置版本选择策略。';


COMMENT ON COLUMN batch.batch_day_replay_session.config_version IS '重放时固定的配置版本。';


COMMENT ON COLUMN batch.batch_day_replay_session.status IS '批量日重放会话状态。';


COMMENT ON COLUMN batch.batch_day_replay_session.trace_id IS '批量日重放链路标识。';


COMMENT ON COLUMN batch.batch_day_replay_session.execution_mode IS 'REPLAY=正式重放；DRY_RUN=无业务副作用整日演练。';


COMMENT ON COLUMN batch.batch_day_replay_session.candidate_source IS 'EXISTING_INSTANCES=历史实例；SCHEDULE_PLAN=按调度计划生成候选。';


COMMENT ON COLUMN batch.batch_day_replay_session.replay_operation_id IS '日期区间重放所属的 ADR-049 父操作；现有单日会话 API 创建的记录为 NULL。';


CREATE SEQUENCE batch.batch_day_replay_session_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.batch_day_replay_session_id_seq OWNED BY batch.batch_day_replay_session.id;


CREATE TABLE archive.batch_day_replay_session_archive (
    id bigint DEFAULT nextval('batch.batch_day_replay_session_id_seq'::regclass) NOT NULL,
    tenant_id character varying(64) NOT NULL,
    calendar_code character varying(128) NOT NULL,
    biz_date date NOT NULL,
    scope character varying(32) NOT NULL,
    scope_payload jsonb,
    result_policy character varying(64) NOT NULL,
    config_version_policy character varying(64) NOT NULL,
    config_version integer,
    reason character varying(1024) NOT NULL,
    approval_id bigint,
    status character varying(32) NOT NULL,
    total_count integer DEFAULT 0 NOT NULL,
    succeeded_count integer DEFAULT 0 NOT NULL,
    failed_count integer DEFAULT 0 NOT NULL,
    in_flight_count integer DEFAULT 0 NOT NULL,
    requested_by character varying(128) NOT NULL,
    approved_by character varying(128),
    started_at timestamp with time zone,
    completed_at timestamp with time zone,
    trace_id character varying(128),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    execution_mode character varying(24) DEFAULT 'REPLAY'::character varying NOT NULL,
    candidate_source character varying(32) DEFAULT 'EXISTING_INSTANCES'::character varying NOT NULL,
    replay_operation_id bigint,
    CONSTRAINT ck_replay_session_scope CHECK (((scope)::text = ANY (ARRAY['ALL'::character varying, 'ALL_FAILED'::character varying, 'SUBSET_JOB_CODES'::character varying, 'OUTPUTS_ONLY'::character varying]))),
    CONSTRAINT ck_replay_session_status CHECK (((status)::text = ANY (ARRAY['PENDING_APPROVAL'::character varying, 'RUNNING'::character varying, 'SUCCEEDED'::character varying, 'PARTIAL_FAILED'::character varying, 'CANCELLED'::character varying]))),
    CONSTRAINT batch_day_replay_session_archive_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE archive.batch_day_replay_session_archive IS 'V110 新增的 batch.batch_day_replay_session 归档镜像。';


COMMENT ON COLUMN archive.batch_day_replay_session_archive.scope IS 'ALL: 当日所有 SUCCESS/FAILED; ALL_FAILED: 当日 FAILED/PARTIAL_FAILED; SUBSET_JOB_CODES: scope_payload 列出 jobCode; OUTPUTS_ONLY: promote 历史版本不重跑';


COMMENT ON COLUMN archive.batch_day_replay_session_archive.scope_payload IS 'SUBSET_JOB_CODES 时存 {"jobCodes": [...]}, OUTPUTS_ONLY 时存 {"versionIds": [...]}';


COMMENT ON COLUMN archive.batch_day_replay_session_archive.result_policy IS '同 RerunRequest.resultPolicy: CREATE_NEW_VERSION / KEEP_BOTH / MANUAL_CONFIRM_EFFECTIVE';


COMMENT ON COLUMN archive.batch_day_replay_session_archive.execution_mode IS '归档镜像：REPLAY 或 DRY_RUN。';


COMMENT ON COLUMN archive.batch_day_replay_session_archive.candidate_source IS '归档镜像：EXISTING_INSTANCES 或 SCHEDULE_PLAN。';


COMMENT ON COLUMN archive.batch_day_replay_session_archive.replay_operation_id IS '子日期会话归档时保留的 ADR-049 父操作身份。';


CREATE TABLE batch.compensation_command (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    command_no character varying(128) NOT NULL,
    compensation_type character varying(32) NOT NULL,
    target_id bigint,
    job_code character varying(128),
    biz_date date,
    batch_no character varying(128),
    related_job_instance_id bigint,
    related_file_id bigint,
    approval_id character varying(128),
    operator_id character varying(64),
    reason character varying(1024),
    strategy character varying(64),
    command_status character varying(32) NOT NULL,
    trace_id character varying(128),
    result_summary jsonb,
    error_code character varying(64),
    error_message character varying(2048),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    finished_at timestamp with time zone,
    error_key character varying(128),
    error_args jsonb,
    source_workflow_run_id bigint,
    request_fingerprint character varying(64),
    CONSTRAINT ck_compensation_command_status CHECK (((command_status)::text = ANY (ARRAY['PENDING'::character varying, 'RUNNING'::character varying, 'SUCCESS'::character varying, 'FAILED'::character varying]))),
    CONSTRAINT ck_compensation_command_type CHECK (((compensation_type)::text = ANY (ARRAY['JOB'::character varying, 'WORKFLOW_NODE'::character varying, 'STEP'::character varying, 'PARTITION'::character varying, 'FILE'::character varying, 'BATCH'::character varying, 'DLQ'::character varying]))),
    CONSTRAINT compensation_command_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.compensation_command IS '补偿命令聚合；记录需回滚或清理的高风险操作。';


COMMENT ON COLUMN batch.compensation_command.compensation_type IS '补偿操作类型。';


COMMENT ON COLUMN batch.compensation_command.strategy IS '补偿执行策略。';


COMMENT ON COLUMN batch.compensation_command.command_status IS '补偿命令状态机。';


COMMENT ON COLUMN batch.compensation_command.trace_id IS '补偿链路标识。';


COMMENT ON COLUMN batch.compensation_command.result_summary IS '补偿结果摘要 JSON。';


COMMENT ON COLUMN batch.compensation_command.error_key IS '同 pipeline_step_run.error_key';


COMMENT ON COLUMN batch.compensation_command.error_args IS '同 pipeline_step_run.error_args';


COMMENT ON COLUMN batch.compensation_command.source_workflow_run_id IS '精确重放失败 JOB 节点时的来源 WorkflowRun；归档后仍保留其历史身份。';


COMMENT ON COLUMN batch.compensation_command.request_fingerprint IS '经批准的补偿执行载荷的 SHA-256 摘要；不包含链路标识和认证操作人信息。';


CREATE SEQUENCE batch.compensation_command_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.compensation_command_id_seq OWNED BY batch.compensation_command.id;


CREATE TABLE archive.compensation_command_archive (
    id bigint DEFAULT nextval('batch.compensation_command_id_seq'::regclass) NOT NULL,
    tenant_id character varying(64) NOT NULL,
    command_no character varying(128) NOT NULL,
    compensation_type character varying(32) NOT NULL,
    target_id bigint,
    job_code character varying(128),
    biz_date date,
    batch_no character varying(128),
    related_job_instance_id bigint,
    related_file_id bigint,
    approval_id character varying(128),
    operator_id character varying(64),
    reason character varying(1024),
    strategy character varying(64),
    command_status character varying(32) NOT NULL,
    trace_id character varying(128),
    result_summary jsonb,
    error_code character varying(64),
    error_message character varying(2048),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    finished_at timestamp with time zone,
    error_key character varying(128),
    error_args jsonb,
    source_workflow_run_id bigint,
    request_fingerprint character varying(64),
    CONSTRAINT ck_compensation_command_status CHECK (((command_status)::text = ANY (ARRAY['PENDING'::character varying, 'RUNNING'::character varying, 'SUCCESS'::character varying, 'FAILED'::character varying]))),
    CONSTRAINT ck_compensation_command_type CHECK (((compensation_type)::text = ANY (ARRAY['JOB'::character varying, 'WORKFLOW_NODE'::character varying, 'STEP'::character varying, 'PARTITION'::character varying, 'FILE'::character varying, 'BATCH'::character varying, 'DLQ'::character varying]))),
    CONSTRAINT pk_compensation_command_archive PRIMARY KEY (id)
);


COMMENT ON TABLE archive.compensation_command_archive IS 'batch.compensation_command 的冷表镜像；用于补偿审计历史查询。';


COMMENT ON COLUMN archive.compensation_command_archive.error_key IS '镜像 batch.compensation_command.error_key';


COMMENT ON COLUMN archive.compensation_command_archive.error_args IS '镜像 batch.compensation_command.error_args';


COMMENT ON COLUMN archive.compensation_command_archive.source_workflow_run_id IS '精确重放失败 JOB 节点所用来源 WorkflowRun 的归档身份。';


COMMENT ON COLUMN archive.compensation_command_archive.request_fingerprint IS '已批准补偿执行载荷的归档 SHA-256 身份摘要。';


CREATE TABLE archive.console_operation_audit_archive (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    aggregate_type character varying(64) NOT NULL,
    aggregate_id character varying(128) NOT NULL,
    action character varying(128) NOT NULL,
    operator_id character varying(128),
    operator_role character varying(64),
    result character varying(16) DEFAULT 'SUCCESS'::character varying NOT NULL,
    error_code character varying(64),
    error_message character varying(1024),
    params jsonb,
    trace_id character varying(128),
    request_id character varying(128),
    ip_hash character varying(64),
    ua_hash character varying(64),
    event_version integer DEFAULT 1 NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_console_operation_audit_result CHECK (((result)::text = ANY (ARRAY['SUCCESS'::character varying, 'FAILED'::character varying]))),
    CONSTRAINT console_operation_audit_archive_pkey PRIMARY KEY (id)
);

ALTER TABLE ONLY archive.console_operation_audit_archive FORCE ROW LEVEL SECURITY;


COMMENT ON TABLE archive.console_operation_audit_archive IS '已闭合终态通知人工操作审计冷存；保留原请求幂等身份。';


COMMENT ON COLUMN archive.console_operation_audit_archive.aggregate_type IS '审计聚合根类型。';


COMMENT ON COLUMN archive.console_operation_audit_archive.params IS '脱敏后的操作参数 JSON。';


COMMENT ON COLUMN archive.console_operation_audit_archive.trace_id IS '控制台操作链路标识。';


COMMENT ON COLUMN archive.console_operation_audit_archive.ip_hash IS '来源 IP 的不可逆审计哈希。';


COMMENT ON COLUMN archive.console_operation_audit_archive.ua_hash IS '浏览器 User-Agent 的不可逆审计哈希。';


COMMENT ON COLUMN archive.console_operation_audit_archive.event_version IS '审计事件契约版本。';


CREATE TABLE archive.console_push_job_delivery_archive (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    terminal_event_key character varying(256) NOT NULL,
    job_instance_id bigint NOT NULL,
    operator_id character varying(128),
    record_type character varying(16) NOT NULL,
    subscription_id bigint,
    subscription_fingerprint character varying(64),
    decision character varying(32),
    event_type character varying(64) NOT NULL,
    payload_json jsonb NOT NULL,
    delivery_status character varying(16),
    attempt integer DEFAULT 0 NOT NULL,
    next_retry_at timestamp with time zone,
    delivery_claim_token uuid,
    delivery_claim_until timestamp with time zone,
    http_status integer,
    error_message character varying(1024),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_job_push_claim CHECK (((delivery_claim_token IS NULL) = (delivery_claim_until IS NULL))),
    CONSTRAINT ck_job_push_record CHECK (((((record_type)::text = 'DECISION'::text) AND (subscription_id IS NULL) AND (subscription_fingerprint IS NULL) AND (decision IS NOT NULL) AND (delivery_status IS NULL)) OR (((record_type)::text = 'DEVICE'::text) AND (subscription_id IS NOT NULL) AND (subscription_id > 0) AND (subscription_fingerprint IS NOT NULL) AND (length((subscription_fingerprint)::text) = 64) AND (operator_id IS NOT NULL) AND (length((operator_id)::text) > 0) AND (decision IS NULL) AND (delivery_status IS NOT NULL)))),
    CONSTRAINT console_push_job_delivery_attempt_check CHECK ((attempt >= 0)),
    CONSTRAINT console_push_job_delivery_decision_check CHECK (((decision)::text = ANY (ARRAY['DISABLED'::character varying, 'DRY_RUN'::character varying, 'NO_OPERATOR'::character varying, 'NO_SUBSCRIPTIONS'::character varying, 'QUEUED'::character varying]))),
    CONSTRAINT console_push_job_delivery_delivery_status_check CHECK (((delivery_status)::text = ANY (ARRAY['PENDING'::character varying, 'IN_FLIGHT'::character varying, 'ACCEPTED'::character varying, 'GONE'::character varying, 'EXHAUSTED'::character varying]))),
    CONSTRAINT console_push_job_delivery_record_type_check CHECK (((record_type)::text = ANY (ARRAY['DECISION'::character varying, 'DEVICE'::character varying]))),
    CONSTRAINT console_push_job_delivery_archive_pkey PRIMARY KEY (id)
);

ALTER TABLE ONLY archive.console_push_job_delivery_archive FORCE ROW LEVEL SECURITY;


COMMENT ON TABLE archive.console_push_job_delivery_archive IS 'Job 终态 Web Push 决定和设备投递冷存；与原通知回执一并迁移。';


COMMENT ON COLUMN archive.console_push_job_delivery_archive.id IS '决定或设备意图的持久身份。';


COMMENT ON COLUMN archive.console_push_job_delivery_archive.tenant_id IS 'Job 事实与收件设备所属租户。';


COMMENT ON COLUMN archive.console_push_job_delivery_archive.terminal_event_key IS '原 Job 终态事实键，与消费回执同租户关联。';


COMMENT ON COLUMN archive.console_push_job_delivery_archive.job_instance_id IS '原 Job 实例身份，不代替 Workflow 根身份。';


COMMENT ON COLUMN archive.console_push_job_delivery_archive.operator_id IS '事实中冻结的提交人；不从当前配置重新推算。';


COMMENT ON COLUMN archive.console_push_job_delivery_archive.record_type IS '不可变首次决定或逐设备待投递意图。';


COMMENT ON COLUMN archive.console_push_job_delivery_archive.subscription_id IS '首次选中的设备订阅；不设外键以允许用户撤销订阅且保留投递证据。';


COMMENT ON COLUMN archive.console_push_job_delivery_archive.subscription_fingerprint IS '原 tenant/user/device/endpoint/keys 的 SHA-256 摘要，不复制设备凭据。';


COMMENT ON COLUMN archive.console_push_job_delivery_archive.decision IS '首次接纳时的不可变开关/无目标/已排队决定；不是当前送达状态。';


COMMENT ON COLUMN archive.console_push_job_delivery_archive.event_type IS 'Job 原终态的推送展示分类，不是新业务事件总线编码。';


COMMENT ON COLUMN archive.console_push_job_delivery_archive.payload_json IS '首次接纳冻结的通知载荷，不含 endpoint 或设备密钥。';


COMMENT ON COLUMN archive.console_push_job_delivery_archive.delivery_status IS '设备投递状态；ACCEPTED 只表示 provider 已接纳。';


COMMENT ON COLUMN archive.console_push_job_delivery_archive.attempt IS '设备投递尝试次数。';


COMMENT ON COLUMN archive.console_push_job_delivery_archive.next_retry_at IS '下次允许尝试时间，空值表示不自动重投。';


COMMENT ON COLUMN archive.console_push_job_delivery_archive.delivery_claim_token IS '认领身份，旧执行者回写必须被围栏拒绝。';


COMMENT ON COLUMN archive.console_push_job_delivery_archive.delivery_claim_until IS '在途认领期限，过期不代表远端未接纳。';


COMMENT ON COLUMN archive.console_push_job_delivery_archive.http_status IS '已观察到的 provider HTTP 状态；未观察到则为空。';


COMMENT ON COLUMN archive.console_push_job_delivery_archive.error_message IS '有界脱敏的失败/未知结果原因。';


COMMENT ON COLUMN archive.console_push_job_delivery_archive.created_at IS '首次接纳或创建设备意图的时间。';


CREATE TABLE batch.data_quality_check (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    job_instance_id bigint NOT NULL,
    rule_id bigint,
    rule_code character varying(128) NOT NULL,
    rule_type character varying(32) NOT NULL,
    severity character varying(16) NOT NULL,
    status character varying(16) NOT NULL,
    metrics_json jsonb,
    failure_sample jsonb,
    error_message character varying(2048),
    checked_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_dq_check_status CHECK (((status)::text = ANY (ARRAY['PASS'::character varying, 'WARN'::character varying, 'FAIL'::character varying, 'SKIPPED'::character varying, 'ERROR'::character varying]))),
    CONSTRAINT data_quality_check_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.data_quality_check IS 'ADR-021 单次 job_instance 的对账检查结果；多条 rule 并行写多行';


COMMENT ON COLUMN batch.data_quality_check.rule_type IS '执行的数据质量规则类型。';


COMMENT ON COLUMN batch.data_quality_check.status IS '质量检查状态。';


COMMENT ON COLUMN batch.data_quality_check.metrics_json IS '质量检查计算指标 JSON。';


CREATE SEQUENCE batch.data_quality_check_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.data_quality_check_id_seq OWNED BY batch.data_quality_check.id;


CREATE TABLE archive.data_quality_check_archive (
    id bigint DEFAULT nextval('batch.data_quality_check_id_seq'::regclass) NOT NULL,
    tenant_id character varying(64) NOT NULL,
    job_instance_id bigint NOT NULL,
    rule_id bigint,
    rule_code character varying(128) NOT NULL,
    rule_type character varying(32) NOT NULL,
    severity character varying(16) NOT NULL,
    status character varying(16) NOT NULL,
    metrics_json jsonb,
    failure_sample jsonb,
    error_message character varying(2048),
    checked_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_dq_check_status CHECK (((status)::text = ANY (ARRAY['PASS'::character varying, 'WARN'::character varying, 'FAIL'::character varying, 'SKIPPED'::character varying, 'ERROR'::character varying]))),
    CONSTRAINT pk_data_quality_check_archive PRIMARY KEY (id)
);


COMMENT ON TABLE archive.data_quality_check_archive IS 'batch.data_quality_check 的冷表镜像。';


CREATE TABLE batch.dead_letter_task (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    source_type character varying(32) NOT NULL,
    source_id bigint NOT NULL,
    dead_letter_reason character varying(1024),
    payload_ref character varying(512),
    replay_status character varying(32) DEFAULT 'NEW'::character varying NOT NULL,
    replay_count integer DEFAULT 0 NOT NULL,
    last_replay_at timestamp with time zone,
    last_replay_result character varying(512),
    trace_id character varying(128),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    next_replay_at timestamp with time zone,
    max_replay_count integer DEFAULT 3 NOT NULL,
    error_class character varying(32) DEFAULT 'SYSTEM'::character varying NOT NULL,
    CONSTRAINT ck_dead_letter_error_class CHECK (((error_class)::text = ANY (ARRAY['BUSINESS'::character varying, 'SYSTEM'::character varying]))),
    CONSTRAINT ck_dead_letter_replay_count CHECK ((replay_count >= 0)),
    CONSTRAINT ck_dead_letter_replay_status CHECK (((replay_status)::text = ANY (ARRAY['NEW'::character varying, 'REPLAYING'::character varying, 'SUCCESS'::character varying, 'FAILED'::character varying, 'GIVE_UP'::character varying]))),
    CONSTRAINT ck_dead_letter_source_type CHECK (((source_type)::text = ANY (ARRAY['JOB_INSTANCE'::character varying, 'JOB_PARTITION'::character varying, 'JOB_TASK'::character varying, 'PIPELINE_INSTANCE'::character varying, 'FILE_DISPATCH'::character varying, 'MQ_MESSAGE'::character varying]))),
    CONSTRAINT dead_letter_task_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.dead_letter_task IS '无法正常处理的任务死信记录；支持受控重放。';


COMMENT ON COLUMN batch.dead_letter_task.source_type IS '死信来源对象类型。';


COMMENT ON COLUMN batch.dead_letter_task.payload_ref IS '原始载荷受控引用。';


COMMENT ON COLUMN batch.dead_letter_task.replay_status IS '死信重放状态。';


COMMENT ON COLUMN batch.dead_letter_task.last_replay_result IS '最近一次自动/手动重放结果摘要或错误信息（截断写入，最长 512）';


COMMENT ON COLUMN batch.dead_letter_task.trace_id IS '死信来源链路标识。';


COMMENT ON COLUMN batch.dead_letter_task.next_replay_at IS 'V90: 下一次自动重放时间, NULL 表示不安排自动重放';


COMMENT ON COLUMN batch.dead_letter_task.max_replay_count IS 'V90: 自动重放上限, 超过后转 GIVE_UP';


COMMENT ON COLUMN batch.dead_letter_task.error_class IS 'V90: BUSINESS|SYSTEM 错误分类, BUSINESS 不参与自动重放';


CREATE SEQUENCE batch.dead_letter_task_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.dead_letter_task_id_seq OWNED BY batch.dead_letter_task.id;


CREATE TABLE archive.dead_letter_task_archive (
    id bigint DEFAULT nextval('batch.dead_letter_task_id_seq'::regclass) NOT NULL,
    tenant_id character varying(64) NOT NULL,
    source_type character varying(32) NOT NULL,
    source_id bigint NOT NULL,
    dead_letter_reason character varying(1024),
    payload_ref character varying(512),
    replay_status character varying(32) DEFAULT 'NEW'::character varying NOT NULL,
    replay_count integer DEFAULT 0 NOT NULL,
    last_replay_at timestamp with time zone,
    last_replay_result character varying(512),
    trace_id character varying(128),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    next_replay_at timestamp with time zone,
    max_replay_count integer DEFAULT 3 NOT NULL,
    error_class character varying(32) DEFAULT 'SYSTEM'::character varying NOT NULL,
    CONSTRAINT ck_dead_letter_error_class CHECK (((error_class)::text = ANY (ARRAY['BUSINESS'::character varying, 'SYSTEM'::character varying]))),
    CONSTRAINT ck_dead_letter_replay_count CHECK ((replay_count >= 0)),
    CONSTRAINT ck_dead_letter_replay_status CHECK (((replay_status)::text = ANY (ARRAY['NEW'::character varying, 'REPLAYING'::character varying, 'SUCCESS'::character varying, 'FAILED'::character varying, 'GIVE_UP'::character varying]))),
    CONSTRAINT ck_dead_letter_source_type CHECK (((source_type)::text = ANY (ARRAY['JOB_INSTANCE'::character varying, 'JOB_PARTITION'::character varying, 'JOB_TASK'::character varying, 'PIPELINE_INSTANCE'::character varying, 'FILE_DISPATCH'::character varying, 'MQ_MESSAGE'::character varying]))),
    CONSTRAINT pk_dead_letter_task_archive PRIMARY KEY (id)
);


COMMENT ON TABLE archive.dead_letter_task_archive IS 'batch.dead_letter_task 的冷表镜像；保留死信和重放历史。';


CREATE TABLE batch.event_delivery_log (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    outbox_event_id bigint NOT NULL,
    event_type character varying(64) NOT NULL,
    event_key character varying(256) NOT NULL,
    target_topic character varying(256) NOT NULL,
    target_worker_id character varying(128),
    delivery_status character varying(32) NOT NULL,
    delivery_attempt integer DEFAULT 1 NOT NULL,
    delivery_summary jsonb,
    error_message character varying(1024),
    trace_id character varying(128),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    error_key character varying(128),
    error_args jsonb,
    outbox_event_created_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_event_delivery_status CHECK (((delivery_status)::text = ANY (ARRAY['PUBLISHED'::character varying, 'FAILED'::character varying, 'GIVE_UP'::character varying]))),
    CONSTRAINT event_delivery_log_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.event_delivery_log IS 'Outbox 事件投递明细；用于消费者和 Topic 投递诊断。';


COMMENT ON COLUMN batch.event_delivery_log.event_type IS '投递事件类型。';


COMMENT ON COLUMN batch.event_delivery_log.event_key IS '投递事件幂等键。';


COMMENT ON COLUMN batch.event_delivery_log.delivery_status IS '投递状态。';


COMMENT ON COLUMN batch.event_delivery_log.delivery_summary IS '投递结果摘要 JSON。';


COMMENT ON COLUMN batch.event_delivery_log.trace_id IS '投递链路标识。';


COMMENT ON COLUMN batch.event_delivery_log.error_key IS '同 job_task.error_key';


COMMENT ON COLUMN batch.event_delivery_log.error_args IS '同 job_task.error_args';


COMMENT ON COLUMN batch.event_delivery_log.outbox_event_created_at IS '被引用 Outbox 事件的分区身份；属于 fk_event_delivery_log_outbox_event_reference 的组成部分。';


CREATE SEQUENCE batch.event_delivery_log_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.event_delivery_log_id_seq OWNED BY batch.event_delivery_log.id;


CREATE TABLE archive.event_delivery_log_archive (
    id bigint DEFAULT nextval('batch.event_delivery_log_id_seq'::regclass) NOT NULL,
    tenant_id character varying(64) NOT NULL,
    outbox_event_id bigint NOT NULL,
    event_type character varying(64) NOT NULL,
    event_key character varying(256) NOT NULL,
    target_topic character varying(256) NOT NULL,
    target_worker_id character varying(128),
    delivery_status character varying(32) NOT NULL,
    delivery_attempt integer DEFAULT 1 NOT NULL,
    delivery_summary jsonb,
    error_message character varying(1024),
    trace_id character varying(128),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    error_key character varying(128),
    error_args jsonb,
    outbox_event_created_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_event_delivery_status CHECK (((delivery_status)::text = ANY (ARRAY['PUBLISHED'::character varying, 'FAILED'::character varying, 'GIVE_UP'::character varying]))),
    CONSTRAINT pk_event_delivery_log_archive PRIMARY KEY (id)
);


COMMENT ON TABLE archive.event_delivery_log_archive IS 'batch.event_delivery_log 的冷表镜像。';


COMMENT ON COLUMN archive.event_delivery_log_archive.error_key IS '镜像 batch.event_delivery_log.error_key';


COMMENT ON COLUMN archive.event_delivery_log_archive.error_args IS '镜像 batch.event_delivery_log.error_args';


CREATE TABLE batch.event_outbox_retry (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    outbox_event_id bigint NOT NULL,
    event_key character varying(256) NOT NULL,
    retry_attempt integer DEFAULT 1 NOT NULL,
    retry_status character varying(32) NOT NULL,
    retry_reason character varying(1024),
    next_retry_at timestamp with time zone,
    trace_id character varying(128),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    outbox_event_created_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_event_outbox_retry_status CHECK (((retry_status)::text = ANY (ARRAY['WAITING'::character varying, 'RUNNING'::character varying, 'SUCCESS'::character varying, 'FAILED'::character varying, 'EXHAUSTED'::character varying, 'CANCELLED'::character varying]))),
    CONSTRAINT event_outbox_retry_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.event_outbox_retry IS 'Outbox 发布失败重试计划。';


COMMENT ON COLUMN batch.event_outbox_retry.retry_attempt IS '当前 Outbox 重试次数。';


COMMENT ON COLUMN batch.event_outbox_retry.retry_status IS 'Outbox 重试状态。';


COMMENT ON COLUMN batch.event_outbox_retry.retry_reason IS '本次重试原因。';


COMMENT ON COLUMN batch.event_outbox_retry.next_retry_at IS '下一次允许发布重试的时点。';


COMMENT ON COLUMN batch.event_outbox_retry.trace_id IS 'Outbox 重试链路标识。';


COMMENT ON COLUMN batch.event_outbox_retry.outbox_event_created_at IS '被引用 Outbox 事件的分区身份；属于 fk_event_outbox_retry_outbox_event_reference 的组成部分。';


CREATE SEQUENCE batch.event_outbox_retry_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.event_outbox_retry_id_seq OWNED BY batch.event_outbox_retry.id;


CREATE TABLE archive.event_outbox_retry_archive (
    id bigint DEFAULT nextval('batch.event_outbox_retry_id_seq'::regclass) NOT NULL,
    tenant_id character varying(64) NOT NULL,
    outbox_event_id bigint NOT NULL,
    event_key character varying(256) NOT NULL,
    retry_attempt integer DEFAULT 1 NOT NULL,
    retry_status character varying(32) NOT NULL,
    retry_reason character varying(1024),
    next_retry_at timestamp with time zone,
    trace_id character varying(128),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    outbox_event_created_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_event_outbox_retry_status CHECK (((retry_status)::text = ANY (ARRAY['WAITING'::character varying, 'RUNNING'::character varying, 'SUCCESS'::character varying, 'FAILED'::character varying, 'EXHAUSTED'::character varying, 'CANCELLED'::character varying]))),
    CONSTRAINT pk_event_outbox_retry_archive PRIMARY KEY (id)
);


COMMENT ON TABLE archive.event_outbox_retry_archive IS 'batch.event_outbox_retry 的冷表镜像。';


CREATE TABLE batch.file_dispatch_record (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    file_id bigint NOT NULL,
    pipeline_instance_id bigint,
    channel_code character varying(128) NOT NULL,
    dispatch_target character varying(256),
    dispatch_status character varying(32) NOT NULL,
    dispatch_attempt integer DEFAULT 1 NOT NULL,
    receipt_code character varying(128),
    receipt_status character varying(32) DEFAULT 'NONE'::character varying NOT NULL,
    external_request_id character varying(128),
    error_code character varying(64),
    error_message character varying(1024),
    dispatched_at timestamp with time zone,
    ack_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    error_key character varying(128),
    error_args jsonb,
    CONSTRAINT ck_file_dispatch_attempt CHECK ((dispatch_attempt > 0)),
    CONSTRAINT ck_file_dispatch_receipt_status CHECK (((receipt_status)::text = ANY (ARRAY['NONE'::character varying, 'PENDING'::character varying, 'SUCCESS'::character varying, 'FAILED'::character varying]))),
    CONSTRAINT ck_file_dispatch_status CHECK (((dispatch_status)::text = ANY (ARRAY['CREATED'::character varying, 'SENT'::character varying, 'ACKED'::character varying, 'FAILED'::character varying, 'COMPENSATED'::character varying]))),
    CONSTRAINT file_dispatch_record_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.file_dispatch_record IS '文件分发尝试和回执记录；与下游通道交互的审计依据。';


COMMENT ON COLUMN batch.file_dispatch_record.dispatch_target IS '本次分发的最终目标引用。';


COMMENT ON COLUMN batch.file_dispatch_record.dispatch_status IS '分发状态机。';


COMMENT ON COLUMN batch.file_dispatch_record.receipt_status IS '下游回执状态。';


COMMENT ON COLUMN batch.file_dispatch_record.external_request_id IS '下游系统返回的请求或受理标识。';


COMMENT ON COLUMN batch.file_dispatch_record.error_code IS '分发标准化错误码。';


COMMENT ON COLUMN batch.file_dispatch_record.error_message IS '分发错误摘要。';


COMMENT ON COLUMN batch.file_dispatch_record.error_key IS '同 pipeline_step_run.error_key';


COMMENT ON COLUMN batch.file_dispatch_record.error_args IS '同 pipeline_step_run.error_args';


CREATE SEQUENCE batch.file_dispatch_record_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.file_dispatch_record_id_seq OWNED BY batch.file_dispatch_record.id;


CREATE TABLE archive.file_dispatch_record_archive (
    id bigint DEFAULT nextval('batch.file_dispatch_record_id_seq'::regclass) NOT NULL,
    tenant_id character varying(64) NOT NULL,
    file_id bigint NOT NULL,
    pipeline_instance_id bigint,
    channel_code character varying(128) NOT NULL,
    dispatch_target character varying(256),
    dispatch_status character varying(32) NOT NULL,
    dispatch_attempt integer DEFAULT 1 NOT NULL,
    receipt_code character varying(128),
    receipt_status character varying(32) DEFAULT 'NONE'::character varying NOT NULL,
    external_request_id character varying(128),
    error_code character varying(64),
    error_message character varying(1024),
    dispatched_at timestamp with time zone,
    ack_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    error_key character varying(128),
    error_args jsonb,
    CONSTRAINT ck_file_dispatch_attempt CHECK ((dispatch_attempt > 0)),
    CONSTRAINT ck_file_dispatch_receipt_status CHECK (((receipt_status)::text = ANY (ARRAY['NONE'::character varying, 'PENDING'::character varying, 'SUCCESS'::character varying, 'FAILED'::character varying]))),
    CONSTRAINT ck_file_dispatch_status CHECK (((dispatch_status)::text = ANY (ARRAY['CREATED'::character varying, 'SENT'::character varying, 'ACKED'::character varying, 'FAILED'::character varying, 'COMPENSATED'::character varying]))),
    CONSTRAINT pk_file_dispatch_record_archive PRIMARY KEY (id)
);


COMMENT ON TABLE archive.file_dispatch_record_archive IS 'batch.file_dispatch_record 的冷表镜像；用于历史分发取证。';


COMMENT ON COLUMN archive.file_dispatch_record_archive.error_key IS '镜像 batch.file_dispatch_record.error_key';


COMMENT ON COLUMN archive.file_dispatch_record_archive.error_args IS '镜像 batch.file_dispatch_record.error_args';


CREATE TABLE batch.file_record (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    file_code character varying(128),
    biz_type character varying(64),
    file_category character varying(32) NOT NULL,
    file_name character varying(512) NOT NULL,
    original_file_name character varying(512),
    file_ext character varying(32),
    file_format_type character varying(32) NOT NULL,
    charset character varying(32),
    mime_type character varying(128),
    file_size_bytes bigint DEFAULT 0 NOT NULL,
    checksum_type character varying(32) DEFAULT 'NONE'::character varying NOT NULL,
    checksum_value character varying(256),
    storage_type character varying(32) NOT NULL,
    storage_path text NOT NULL,
    storage_bucket character varying(256),
    file_version character varying(64),
    file_generation_no integer DEFAULT 1 NOT NULL,
    is_latest boolean DEFAULT true NOT NULL,
    source_type character varying(32) NOT NULL,
    source_ref character varying(256),
    file_status character varying(32) NOT NULL,
    biz_date date,
    trace_id character varying(128),
    metadata_json jsonb,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    arrival_channel_code character varying(128),
    legacy_channel_fallback boolean DEFAULT false NOT NULL,
    CONSTRAINT ck_file_record_category_v2 CHECK (((file_category)::text = ANY (ARRAY['INPUT'::character varying, 'OUTPUT'::character varying, 'INTERMEDIATE'::character varying, 'ARCHIVE'::character varying, 'EXPORT_TRIGGER'::character varying]))),
    CONSTRAINT ck_file_record_checksum_type CHECK (((checksum_type)::text = ANY (ARRAY['NONE'::character varying, 'MD5'::character varying, 'SHA-1'::character varying, 'SHA-256'::character varying]))),
    CONSTRAINT ck_file_record_file_size CHECK ((file_size_bytes >= 0)),
    CONSTRAINT ck_file_record_format CHECK (((file_format_type)::text = ANY (ARRAY['DELIMITED'::character varying, 'FIXED_WIDTH'::character varying, 'EXCEL'::character varying, 'XML'::character varying, 'JSON'::character varying, 'BINARY'::character varying]))),
    CONSTRAINT ck_file_record_generation_no CHECK ((file_generation_no > 0)),
    CONSTRAINT ck_file_record_source_type CHECK (((source_type)::text = ANY (ARRAY['UPLOAD'::character varying, 'SFTP'::character varying, 'API'::character varying, 'GENERATED'::character varying, 'SYSTEM'::character varying]))),
    CONSTRAINT ck_file_record_status CHECK (((file_status)::text = ANY (ARRAY['RECEIVED'::character varying, 'PARSING'::character varying, 'PARSED'::character varying, 'VALIDATED'::character varying, 'LOADED'::character varying, 'GENERATED'::character varying, 'DISPATCHING'::character varying, 'DISPATCHED'::character varying, 'ARCHIVED'::character varying, 'FAILED'::character varying, 'DELETED'::character varying]))),
    CONSTRAINT ck_file_record_storage_type CHECK (((storage_type)::text = ANY (ARRAY['LOCAL'::character varying, 'NAS'::character varying, 'S3'::character varying, 'OSS'::character varying, 'HDFS'::character varying, 'DB_BLOB'::character varying]))),
    CONSTRAINT file_record_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.file_record IS '文件接收、完整性校验、存储与生命周期的权威记录。';


COMMENT ON COLUMN batch.file_record.biz_type IS '文件所属业务类型。';


COMMENT ON COLUMN batch.file_record.file_format_type IS '文件格式类型。';


COMMENT ON COLUMN batch.file_record.mime_type IS '文件媒体类型。';


COMMENT ON COLUMN batch.file_record.checksum_type IS '完整性校验算法。';


COMMENT ON COLUMN batch.file_record.checksum_value IS '按 checksum_type 计算的内容摘要。';


COMMENT ON COLUMN batch.file_record.storage_type IS '对象存储后端类型；决定 storage_path 的解释方式。';


COMMENT ON COLUMN batch.file_record.storage_path IS '对象存储定位路径；不包含访问凭据。';


COMMENT ON COLUMN batch.file_record.file_version IS '同业务文件的版本号或供应方版本标识。';


COMMENT ON COLUMN batch.file_record.source_type IS '文件到达来源类型。';


COMMENT ON COLUMN batch.file_record.source_ref IS '来源系统或上传会话引用。';


COMMENT ON COLUMN batch.file_record.file_status IS '文件生命周期状态机。';


COMMENT ON COLUMN batch.file_record.trace_id IS '文件链路标识。';


COMMENT ON COLUMN batch.file_record.metadata_json IS '文件扩展元数据 JSON。';


COMMENT ON COLUMN batch.file_record.arrival_channel_code IS '由可信入口登记的文件到达渠道编码；未建立身份时为空。';


COMMENT ON COLUMN batch.file_record.legacy_channel_fallback IS '迁移前已存在的历史记录允许沿用来源类型匹配。';


CREATE SEQUENCE batch.file_record_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.file_record_id_seq OWNED BY batch.file_record.id;


CREATE TABLE archive.file_record_archive (
    id bigint DEFAULT nextval('batch.file_record_id_seq'::regclass) NOT NULL,
    tenant_id character varying(64) NOT NULL,
    file_code character varying(128),
    biz_type character varying(64),
    file_category character varying(32) NOT NULL,
    file_name character varying(512) NOT NULL,
    original_file_name character varying(512),
    file_ext character varying(32),
    file_format_type character varying(32) NOT NULL,
    charset character varying(32),
    mime_type character varying(128),
    file_size_bytes bigint DEFAULT 0 NOT NULL,
    checksum_type character varying(32) DEFAULT 'NONE'::character varying NOT NULL,
    checksum_value character varying(256),
    storage_type character varying(32) NOT NULL,
    storage_path text NOT NULL,
    storage_bucket character varying(256),
    file_version character varying(64),
    file_generation_no integer DEFAULT 1 NOT NULL,
    is_latest boolean DEFAULT true NOT NULL,
    source_type character varying(32) NOT NULL,
    source_ref character varying(256),
    file_status character varying(32) NOT NULL,
    biz_date date,
    trace_id character varying(128),
    metadata_json jsonb,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    arrival_channel_code character varying(128),
    legacy_channel_fallback boolean DEFAULT false NOT NULL,
    CONSTRAINT ck_file_record_category_v2 CHECK (((file_category)::text = ANY (ARRAY['INPUT'::character varying, 'OUTPUT'::character varying, 'INTERMEDIATE'::character varying, 'ARCHIVE'::character varying, 'EXPORT_TRIGGER'::character varying]))),
    CONSTRAINT ck_file_record_checksum_type CHECK (((checksum_type)::text = ANY (ARRAY['NONE'::character varying, 'MD5'::character varying, 'SHA-1'::character varying, 'SHA-256'::character varying]))),
    CONSTRAINT ck_file_record_file_size CHECK ((file_size_bytes >= 0)),
    CONSTRAINT ck_file_record_format CHECK (((file_format_type)::text = ANY (ARRAY['DELIMITED'::character varying, 'FIXED_WIDTH'::character varying, 'EXCEL'::character varying, 'XML'::character varying, 'JSON'::character varying, 'BINARY'::character varying]))),
    CONSTRAINT ck_file_record_generation_no CHECK ((file_generation_no > 0)),
    CONSTRAINT ck_file_record_source_type CHECK (((source_type)::text = ANY (ARRAY['UPLOAD'::character varying, 'SFTP'::character varying, 'API'::character varying, 'GENERATED'::character varying, 'SYSTEM'::character varying]))),
    CONSTRAINT ck_file_record_status CHECK (((file_status)::text = ANY (ARRAY['RECEIVED'::character varying, 'PARSING'::character varying, 'PARSED'::character varying, 'VALIDATED'::character varying, 'LOADED'::character varying, 'GENERATED'::character varying, 'DISPATCHING'::character varying, 'DISPATCHED'::character varying, 'ARCHIVED'::character varying, 'FAILED'::character varying, 'DELETED'::character varying]))),
    CONSTRAINT ck_file_record_storage_type CHECK (((storage_type)::text = ANY (ARRAY['LOCAL'::character varying, 'NAS'::character varying, 'S3'::character varying, 'OSS'::character varying, 'HDFS'::character varying, 'DB_BLOB'::character varying]))),
    CONSTRAINT pk_file_record_archive PRIMARY KEY (id)
);


COMMENT ON TABLE archive.file_record_archive IS 'batch.file_record 的冷表镜像;用于历史文件证据链和归档后 forensic 查询';


COMMENT ON COLUMN archive.file_record_archive.arrival_channel_code IS '归档时保留可信文件到达渠道编码。';


COMMENT ON COLUMN archive.file_record_archive.legacy_channel_fallback IS '归档时保留历史来源匹配资格。';


CREATE TABLE batch.job_execution_log (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    job_instance_id bigint,
    job_partition_id bigint,
    log_level character varying(16) NOT NULL,
    log_type character varying(32) NOT NULL,
    trace_id character varying(128),
    message character varying(2048) NOT NULL,
    detail_ref character varying(512),
    extra_json jsonb,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_job_execution_log_level CHECK (((log_level)::text = ANY (ARRAY['DEBUG'::character varying, 'INFO'::character varying, 'WARN'::character varying, 'ERROR'::character varying]))),
    CONSTRAINT ck_job_execution_log_partition_requires_instance CHECK (((job_partition_id IS NULL) OR (job_instance_id IS NOT NULL))),
    CONSTRAINT ck_job_execution_log_type CHECK (((log_type)::text = ANY (ARRAY['SYSTEM'::character varying, 'BUSINESS'::character varying, 'RETRY'::character varying, 'ALARM'::character varying, 'AUDIT'::character varying, 'COMPENSATION'::character varying, 'COMPENSATION_REJECTED'::character varying, 'COMPENSATION_SUBMIT'::character varying, 'COMPENSATION_FAILED'::character varying]))),
    CONSTRAINT job_execution_log_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.job_execution_log IS '作业执行过程日志索引；正文存储由日志系统负责。';


COMMENT ON COLUMN batch.job_execution_log.log_type IS '执行日志分类。';


COMMENT ON COLUMN batch.job_execution_log.trace_id IS '执行日志链路标识。';


COMMENT ON COLUMN batch.job_execution_log.extra_json IS '执行日志扩展信息 JSON。';


COMMENT ON CONSTRAINT ck_job_execution_log_type ON batch.job_execution_log IS 'V86 (2026-05-03) 扩展白名单: 加 COMPENSATION_REJECTED 修复 audit 写入路径; 顺带加 SUBMIT/FAILED 子类预留';


CREATE SEQUENCE batch.job_execution_log_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.job_execution_log_id_seq OWNED BY batch.job_execution_log.id;


CREATE TABLE archive.job_execution_log_archive (
    id bigint DEFAULT nextval('batch.job_execution_log_id_seq'::regclass) NOT NULL,
    tenant_id character varying(64) NOT NULL,
    job_instance_id bigint,
    job_partition_id bigint,
    log_level character varying(16) NOT NULL,
    log_type character varying(32) NOT NULL,
    trace_id character varying(128),
    message character varying(2048) NOT NULL,
    detail_ref character varying(512),
    extra_json jsonb,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_job_execution_log_level CHECK (((log_level)::text = ANY (ARRAY['DEBUG'::character varying, 'INFO'::character varying, 'WARN'::character varying, 'ERROR'::character varying]))),
    CONSTRAINT ck_job_execution_log_type CHECK (((log_type)::text = ANY (ARRAY['SYSTEM'::character varying, 'BUSINESS'::character varying, 'RETRY'::character varying, 'ALARM'::character varying, 'AUDIT'::character varying, 'COMPENSATION'::character varying]))),
    CONSTRAINT pk_job_execution_log_archive PRIMARY KEY (id)
);


COMMENT ON TABLE archive.job_execution_log_archive IS 'batch.job_execution_log 的冷表镜像。';


CREATE TABLE batch.job_instance (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    job_definition_id bigint NOT NULL,
    trigger_request_id bigint,
    job_code character varying(128) NOT NULL,
    instance_no character varying(128) NOT NULL,
    biz_date date NOT NULL,
    trigger_type character varying(32) NOT NULL,
    instance_status character varying(32) NOT NULL,
    queue_code character varying(128),
    worker_group character varying(128),
    priority integer DEFAULT 5 NOT NULL,
    dedup_key character varying(256) NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    expected_partition_count integer DEFAULT 0 NOT NULL,
    success_partition_count integer DEFAULT 0 NOT NULL,
    failed_partition_count integer DEFAULT 0 NOT NULL,
    trace_id character varying(128),
    params_snapshot jsonb,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deadline_at timestamp with time zone,
    expected_duration_seconds integer DEFAULT 0 NOT NULL,
    sla_alerted_at timestamp with time zone,
    batch_no character varying(128),
    operator_id character varying(64),
    rerun_flag boolean DEFAULT false NOT NULL,
    retry_flag boolean DEFAULT false NOT NULL,
    rerun_reason character varying(512),
    related_file_id bigint,
    parent_instance_id bigint,
    result_summary jsonb,
    run_attempt integer DEFAULT 1 NOT NULL,
    high_water_mark_in character varying(64),
    high_water_mark_out character varying(64),
    calendar_code character varying(64),
    data_interval_start timestamp with time zone,
    data_interval_end timestamp with time zone,
    job_definition_version integer,
    rerun_policy_snapshot jsonb DEFAULT '{}'::jsonb NOT NULL,
    replay_session_id bigint,
    failure_class character varying(32),
    dry_run boolean DEFAULT false NOT NULL,
    cancelled_partition_count integer DEFAULT 0 NOT NULL,
    terminated_partition_count integer DEFAULT 0 NOT NULL,
    execution_started_at timestamp with time zone,
    CONSTRAINT ck_job_instance_cancelled_partition_count CHECK ((cancelled_partition_count >= 0)),
    CONSTRAINT ck_job_instance_data_interval_pair CHECK ((((data_interval_start IS NULL) AND (data_interval_end IS NULL)) OR ((data_interval_start IS NOT NULL) AND (data_interval_end IS NOT NULL) AND (data_interval_start < data_interval_end)))),
    CONSTRAINT ck_job_instance_expected_duration_seconds CHECK ((expected_duration_seconds >= 0)),
    CONSTRAINT ck_job_instance_expected_partition_count CHECK ((expected_partition_count >= 0)),
    CONSTRAINT ck_job_instance_failed_partition_count CHECK ((failed_partition_count >= 0)),
    CONSTRAINT ck_job_instance_failure_class CHECK (((failure_class IS NULL) OR ((failure_class)::text = ANY (ARRAY['INFRASTRUCTURE'::character varying, 'DATA_QUALITY'::character varying, 'BUSINESS_RULE'::character varying, 'CONFIG'::character varying, 'UPSTREAM_DELAY'::character varying, 'TIMEOUT'::character varying, 'UNKNOWN'::character varying])))),
    CONSTRAINT ck_job_instance_priority CHECK (((priority >= 1) AND (priority <= 9))),
    CONSTRAINT ck_job_instance_run_attempt CHECK ((run_attempt >= 1)),
    CONSTRAINT ck_job_instance_status CHECK (((instance_status)::text = ANY (ARRAY['CREATED'::character varying, 'WAITING'::character varying, 'READY'::character varying, 'RUNNING'::character varying, 'PAUSED'::character varying, 'PARTIAL_FAILED'::character varying, 'SUCCESS'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying, 'TERMINATED'::character varying, 'SUCCESS_DRY_RUN'::character varying, 'FAILED_DRY_RUN'::character varying]))),
    CONSTRAINT ck_job_instance_success_partition_count CHECK ((success_partition_count >= 0)),
    CONSTRAINT ck_job_instance_terminated_partition_count CHECK ((terminated_partition_count >= 0)),
    CONSTRAINT ck_job_instance_trigger_source CHECK (((trigger_request_id IS NOT NULL) OR ((trigger_type)::text = 'MANUAL'::text))),
    CONSTRAINT ck_job_instance_trigger_type CHECK (((trigger_type)::text = ANY (ARRAY['SCHEDULED'::character varying, 'API'::character varying, 'MANUAL'::character varying, 'EVENT'::character varying, 'CATCH_UP'::character varying, 'RERUN'::character varying]))),
    CONSTRAINT job_instance_p_pkey PRIMARY KEY (id, biz_date)
)
PARTITION BY RANGE (biz_date);


COMMENT ON TABLE batch.job_instance IS '一次作业运行实例；状态机和批量日执行的权威运行态。';


COMMENT ON COLUMN batch.job_instance.trigger_type IS '本实例的实际触发来源。';


COMMENT ON COLUMN batch.job_instance.instance_status IS '实例状态机；终态不得被后续写入复活。';


COMMENT ON COLUMN batch.job_instance.worker_group IS '本实例调度目标 worker 组快照。';


COMMENT ON COLUMN batch.job_instance.priority IS '本实例调度优先级快照。';


COMMENT ON COLUMN batch.job_instance.dedup_key IS '调用方或调度器提供的业务幂等键；与 run_attempt 组成实例幂等语义。';


COMMENT ON COLUMN batch.job_instance.version IS 'CAS 乐观锁版本。';


COMMENT ON COLUMN batch.job_instance.trace_id IS '端到端可观测链路标识。';


COMMENT ON COLUMN batch.job_instance.params_snapshot IS '创建时冻结的定义参数 JSON；运行期间不追随定义变更。';


COMMENT ON COLUMN batch.job_instance.retry_flag IS '是否由失败重试或重放创建的实例。';


COMMENT ON COLUMN batch.job_instance.result_summary IS '实例汇总结果 JSON；由任务完成后聚合写入。';


COMMENT ON COLUMN batch.job_instance.run_attempt IS '同一 dedup_key 的运行尝试号；重跑递增且参与唯一约束。';


COMMENT ON COLUMN batch.job_instance.job_definition_version IS '启动实例时固定的任务定义版本。';


COMMENT ON COLUMN batch.job_instance.rerun_policy_snapshot IS '启动实例时固定的重跑策略快照 JSON。';


COMMENT ON COLUMN batch.job_instance.failure_class IS '失败分类；驱动按失败类别的重试策略。';


COMMENT ON COLUMN batch.job_instance.cancelled_partition_count IS '以 CANCELLED 状态完成的分区数量。';


COMMENT ON COLUMN batch.job_instance.terminated_partition_count IS '以 TERMINATED 状态完成的分区数量。';


COMMENT ON COLUMN batch.job_instance.execution_started_at IS '当前租约所有者在执行线程进入适配器前确认的首次执行入口时间；与 started_at 派发时间独立。';


CREATE SEQUENCE batch.job_instance_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.job_instance_id_seq OWNED BY batch.job_instance.id;


CREATE TABLE archive.job_instance_archive (
    id bigint DEFAULT nextval('batch.job_instance_id_seq'::regclass) NOT NULL,
    tenant_id character varying(64) NOT NULL,
    job_definition_id bigint NOT NULL,
    trigger_request_id bigint,
    job_code character varying(128) NOT NULL,
    instance_no character varying(128) NOT NULL,
    biz_date date,
    trigger_type character varying(32) NOT NULL,
    instance_status character varying(32) NOT NULL,
    queue_code character varying(128),
    worker_group character varying(128),
    priority integer DEFAULT 5 NOT NULL,
    dedup_key character varying(256) NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    expected_partition_count integer DEFAULT 0 NOT NULL,
    success_partition_count integer DEFAULT 0 NOT NULL,
    failed_partition_count integer DEFAULT 0 NOT NULL,
    trace_id character varying(128),
    params_snapshot jsonb,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deadline_at timestamp with time zone,
    expected_duration_seconds integer DEFAULT 0 NOT NULL,
    sla_alerted_at timestamp with time zone,
    batch_no character varying(128),
    operator_id character varying(64),
    rerun_flag boolean DEFAULT false NOT NULL,
    retry_flag boolean DEFAULT false NOT NULL,
    rerun_reason character varying(512),
    related_file_id bigint,
    parent_instance_id bigint,
    result_summary jsonb,
    run_attempt integer DEFAULT 1 NOT NULL,
    high_water_mark_in character varying(64),
    high_water_mark_out character varying(64),
    calendar_code character varying(64),
    data_interval_start timestamp with time zone,
    data_interval_end timestamp with time zone,
    job_definition_version integer,
    rerun_policy_snapshot jsonb DEFAULT '{}'::jsonb NOT NULL,
    replay_session_id bigint,
    failure_class character varying(32),
    dry_run boolean DEFAULT false NOT NULL,
    cancelled_partition_count integer DEFAULT 0 NOT NULL,
    terminated_partition_count integer DEFAULT 0 NOT NULL,
    execution_started_at timestamp with time zone,
    CONSTRAINT ck_job_instance_archive_cancelled_partition_count CHECK ((cancelled_partition_count >= 0)),
    CONSTRAINT ck_job_instance_archive_terminated_partition_count CHECK ((terminated_partition_count >= 0)),
    CONSTRAINT ck_job_instance_archive_trigger_source CHECK (((trigger_request_id IS NOT NULL) OR ((trigger_type)::text = 'MANUAL'::text))),
    CONSTRAINT ck_job_instance_expected_duration_seconds CHECK ((expected_duration_seconds >= 0)),
    CONSTRAINT ck_job_instance_expected_partition_count CHECK ((expected_partition_count >= 0)),
    CONSTRAINT ck_job_instance_failed_partition_count CHECK ((failed_partition_count >= 0)),
    CONSTRAINT ck_job_instance_priority CHECK (((priority >= 1) AND (priority <= 9))),
    CONSTRAINT ck_job_instance_run_attempt CHECK ((run_attempt >= 1)),
    CONSTRAINT ck_job_instance_status CHECK (((instance_status)::text = ANY (ARRAY['CREATED'::character varying, 'WAITING'::character varying, 'READY'::character varying, 'RUNNING'::character varying, 'PAUSED'::character varying, 'PARTIAL_FAILED'::character varying, 'SUCCESS'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying, 'TERMINATED'::character varying, 'SUCCESS_DRY_RUN'::character varying, 'FAILED_DRY_RUN'::character varying]))),
    CONSTRAINT ck_job_instance_success_partition_count CHECK ((success_partition_count >= 0)),
    CONSTRAINT ck_job_instance_trigger_type CHECK (((trigger_type)::text = ANY (ARRAY['SCHEDULED'::character varying, 'API'::character varying, 'MANUAL'::character varying, 'EVENT'::character varying, 'CATCH_UP'::character varying, 'RERUN'::character varying]))),
    CONSTRAINT pk_job_instance_archive PRIMARY KEY (id)
);


COMMENT ON TABLE archive.job_instance_archive IS 'batch.job_instance 的冷表镜像；用于历史运行查询。';


COMMENT ON COLUMN archive.job_instance_archive.calendar_code IS 'V93/V98 镜像列：创建时日历快照，自 batch.job_instance 归档';


COMMENT ON COLUMN archive.job_instance_archive.data_interval_start IS 'V94/V97 镜像列：半开区间起点，自 batch.job_instance 归档';


COMMENT ON COLUMN archive.job_instance_archive.data_interval_end IS 'V94/V97 镜像列：半开区间终点，自 batch.job_instance 归档';


COMMENT ON COLUMN archive.job_instance_archive.job_definition_version IS 'V103 镜像字段：从 batch.job_instance 复制的 job_definition.version 快照。';


COMMENT ON COLUMN archive.job_instance_archive.rerun_policy_snapshot IS 'V103 镜像字段：从 batch.job_instance 复制的重跑策略快照。';


COMMENT ON COLUMN archive.job_instance_archive.replay_session_id IS 'V108 新增的镜像字段。';


COMMENT ON COLUMN archive.job_instance_archive.cancelled_partition_count IS '以 CANCELLED 状态完成的分区数量。';


COMMENT ON COLUMN archive.job_instance_archive.terminated_partition_count IS '以 TERMINATED 状态完成的分区数量。';


COMMENT ON COLUMN archive.job_instance_archive.execution_started_at IS '归档保留的首次实际执行入口时间，不从派发或认领时间推算。';


CREATE TABLE batch.job_partition (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    job_instance_id bigint NOT NULL,
    partition_no integer NOT NULL,
    partition_key character varying(256),
    partition_status character varying(32) NOT NULL,
    worker_group character varying(128),
    worker_code character varying(128),
    lease_expire_at timestamp with time zone,
    retry_count integer DEFAULT 0 NOT NULL,
    business_key character varying(256),
    idempotency_key character varying(512),
    input_snapshot jsonb,
    output_summary jsonb,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    current_invocation_id character varying(64),
    invocation_started_at timestamp with time zone,
    dry_run boolean DEFAULT false NOT NULL,
    source_file_id bigint,
    template_code character varying(128),
    target_ref character varying(256),
    job_instance_biz_date date,
    CONSTRAINT ck_job_partition_instance_biz_date_not_null CHECK ((job_instance_biz_date IS NOT NULL)),
    CONSTRAINT ck_job_partition_no CHECK ((partition_no >= 0)),
    CONSTRAINT ck_job_partition_retry_count CHECK ((retry_count >= 0)),
    CONSTRAINT ck_job_partition_status CHECK (((partition_status)::text = ANY (ARRAY['CREATED'::character varying, 'WAITING'::character varying, 'READY'::character varying, 'RUNNING'::character varying, 'SUCCESS'::character varying, 'FAILED'::character varying, 'RETRYING'::character varying, 'CANCELLED'::character varying, 'TERMINATED'::character varying]))),
    CONSTRAINT job_partition_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.job_partition IS 'job_instance 的可并行分片；资源调度和 worker 领取的最小工作单元。';


COMMENT ON COLUMN batch.job_partition.partition_key IS '业务分片键；同一实例内标识分片边界。';


COMMENT ON COLUMN batch.job_partition.partition_status IS '分片状态机；WAITING、READY、RUNNING 和终态由生命周期服务推进。';


COMMENT ON COLUMN batch.job_partition.worker_group IS '分片目标 worker 组快照。';


COMMENT ON COLUMN batch.job_partition.worker_code IS '实际领取该分片的 worker 标识。';


COMMENT ON COLUMN batch.job_partition.retry_count IS '已发生的分片重试次数。';


COMMENT ON COLUMN batch.job_partition.business_key IS '分片对应的业务数据范围标识。';


COMMENT ON COLUMN batch.job_partition.idempotency_key IS '分片执行幂等键；下游写入或重放必须使用。';


COMMENT ON COLUMN batch.job_partition.version IS 'CAS 乐观锁版本。';


COMMENT ON COLUMN batch.job_partition.dry_run IS 'ADR-026 dry-run 演练；从父 instance.dry_run 透传，与父值一致';


COMMENT ON COLUMN batch.job_partition.source_file_id IS '文件型分片的输入 file_record 标识。';


COMMENT ON COLUMN batch.job_partition.template_code IS '文件型分片使用的文件模板编码快照。';


COMMENT ON COLUMN batch.job_partition.target_ref IS '分片目标位置引用；不得包含明文凭据。';


COMMENT ON COLUMN batch.job_partition.job_instance_biz_date IS '从所属 JobInstance 复制的业务日分区键，用于由数据库原生外键保障租户隔离。';


CREATE SEQUENCE batch.job_partition_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.job_partition_id_seq OWNED BY batch.job_partition.id;


CREATE TABLE archive.job_partition_archive (
    id bigint DEFAULT nextval('batch.job_partition_id_seq'::regclass) NOT NULL,
    tenant_id character varying(64) NOT NULL,
    job_instance_id bigint NOT NULL,
    partition_no integer NOT NULL,
    partition_key character varying(256),
    partition_status character varying(32) NOT NULL,
    worker_group character varying(128),
    worker_code character varying(128),
    lease_expire_at timestamp with time zone,
    retry_count integer DEFAULT 0 NOT NULL,
    business_key character varying(256),
    idempotency_key character varying(512),
    input_snapshot jsonb,
    output_summary jsonb,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    current_invocation_id character varying(64),
    invocation_started_at timestamp with time zone,
    dry_run boolean DEFAULT false NOT NULL,
    source_file_id bigint,
    template_code character varying(128),
    target_ref character varying(256),
    job_instance_biz_date date,
    CONSTRAINT ck_job_partition_no CHECK ((partition_no >= 0)),
    CONSTRAINT ck_job_partition_retry_count CHECK ((retry_count >= 0)),
    CONSTRAINT ck_job_partition_status CHECK (((partition_status)::text = ANY (ARRAY['CREATED'::character varying, 'WAITING'::character varying, 'READY'::character varying, 'RUNNING'::character varying, 'SUCCESS'::character varying, 'FAILED'::character varying, 'RETRYING'::character varying, 'CANCELLED'::character varying, 'TERMINATED'::character varying]))),
    CONSTRAINT pk_job_partition_archive PRIMARY KEY (id)
);


COMMENT ON TABLE archive.job_partition_archive IS 'batch.job_partition 的冷表镜像。';


CREATE TABLE batch.job_step_instance (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    job_instance_id bigint NOT NULL,
    job_partition_id bigint,
    job_task_id bigint NOT NULL,
    step_code character varying(128) NOT NULL,
    step_type character varying(64) NOT NULL,
    step_status character varying(32) NOT NULL,
    retry_count integer DEFAULT 0 NOT NULL,
    related_file_id bigint,
    result_summary jsonb,
    error_code character varying(64),
    error_message character varying(2048),
    version bigint DEFAULT 0 NOT NULL,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    error_key character varying(128),
    error_args jsonb,
    job_partition_ref_key bigint GENERATED ALWAYS AS (COALESCE(job_partition_id, (0)::bigint)) STORED,
    CONSTRAINT ck_job_step_instance_retry CHECK ((retry_count >= 0)),
    CONSTRAINT ck_job_step_instance_status CHECK (((step_status)::text = ANY (ARRAY['CREATED'::character varying, 'WAITING'::character varying, 'READY'::character varying, 'RUNNING'::character varying, 'SUCCESS'::character varying, 'FAILED'::character varying, 'RETRYING'::character varying, 'CANCELLED'::character varying, 'TERMINATED'::character varying]))),
    CONSTRAINT ck_job_step_partition_reference_nonzero CHECK ((job_partition_id IS DISTINCT FROM 0)),
    CONSTRAINT job_step_instance_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.job_step_instance IS '作业实例内 pipeline 步骤的运行明细；用于进度、重试与诊断。';


COMMENT ON COLUMN batch.job_step_instance.step_type IS '步骤执行类型。';


COMMENT ON COLUMN batch.job_step_instance.step_status IS '步骤实例状态。';


COMMENT ON COLUMN batch.job_step_instance.retry_count IS '步骤实例当前重试次数。';


COMMENT ON COLUMN batch.job_step_instance.version IS '步骤实例乐观锁版本。';


COMMENT ON COLUMN batch.job_step_instance.error_key IS '同 pipeline_step_run.error_key';


COMMENT ON COLUMN batch.job_step_instance.error_args IS '同 pipeline_step_run.error_args';


COMMENT ON COLUMN batch.job_step_instance.job_partition_ref_key IS '由数据库生成的分区身份，用于原生 Step-to-Task 所有权外键。';


CREATE SEQUENCE batch.job_step_instance_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.job_step_instance_id_seq OWNED BY batch.job_step_instance.id;


CREATE TABLE archive.job_step_instance_archive (
    id bigint DEFAULT nextval('batch.job_step_instance_id_seq'::regclass) NOT NULL,
    tenant_id character varying(64) NOT NULL,
    job_instance_id bigint NOT NULL,
    job_partition_id bigint,
    job_task_id bigint NOT NULL,
    step_code character varying(128) NOT NULL,
    step_type character varying(64) NOT NULL,
    step_status character varying(32) NOT NULL,
    retry_count integer DEFAULT 0 NOT NULL,
    related_file_id bigint,
    result_summary jsonb,
    error_code character varying(64),
    error_message character varying(2048),
    version bigint DEFAULT 0 NOT NULL,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    error_key character varying(128),
    error_args jsonb,
    job_partition_ref_key bigint GENERATED ALWAYS AS (COALESCE(job_partition_id, (0)::bigint)) STORED,
    CONSTRAINT ck_job_step_instance_retry CHECK ((retry_count >= 0)),
    CONSTRAINT ck_job_step_instance_status CHECK (((step_status)::text = ANY (ARRAY['CREATED'::character varying, 'WAITING'::character varying, 'READY'::character varying, 'RUNNING'::character varying, 'SUCCESS'::character varying, 'FAILED'::character varying, 'RETRYING'::character varying, 'CANCELLED'::character varying, 'TERMINATED'::character varying]))),
    CONSTRAINT pk_job_step_instance_archive PRIMARY KEY (id)
);


COMMENT ON TABLE archive.job_step_instance_archive IS 'batch.job_step_instance 的冷表镜像。';


COMMENT ON COLUMN archive.job_step_instance_archive.error_key IS '镜像 batch.job_step_instance.error_key';


COMMENT ON COLUMN archive.job_step_instance_archive.error_args IS '镜像 batch.job_step_instance.error_args';


COMMENT ON COLUMN archive.job_step_instance_archive.job_partition_ref_key IS '生成的 Step 分区引用身份的冷归档镜像。';


CREATE TABLE batch.job_task (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    job_instance_id bigint NOT NULL,
    job_partition_id bigint,
    task_type character varying(32) DEFAULT 'EXECUTION'::character varying NOT NULL,
    task_seq integer DEFAULT 1 NOT NULL,
    task_status character varying(32) NOT NULL,
    assigned_worker_code character varying(128),
    task_payload jsonb,
    result_summary jsonb,
    error_code character varying(64),
    error_message character varying(2048),
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    error_key character varying(128),
    error_args jsonb,
    priority integer DEFAULT 5 NOT NULL,
    failure_class character varying(32),
    dry_run boolean DEFAULT false NOT NULL,
    effective_parameters jsonb,
    heartbeat_details jsonb,
    heartbeat_at timestamp with time zone,
    cancel_requested boolean DEFAULT false NOT NULL,
    task_timeout_seconds integer,
    process_staging_generation_id uuid,
    process_staging_batch_key text,
    job_partition_ref_key bigint GENERATED ALWAYS AS (COALESCE(job_partition_id, (0)::bigint)) STORED,
    job_instance_biz_date date NOT NULL,
    non_idempotent_started_at timestamp with time zone,
    external_outcome_resolution_version bigint,
    external_outcome_resolution_trigger_request_id bigint,
    external_outcome_resolution_consumed_at timestamp with time zone,
    execution_attempt_id character varying(64),
    CONSTRAINT ck_job_task_external_outcome_resolution_consumed CHECK (((external_outcome_resolution_consumed_at IS NULL) OR (external_outcome_resolution_version IS NOT NULL))),
    CONSTRAINT ck_job_task_failure_class CHECK (((failure_class IS NULL) OR ((failure_class)::text = ANY (ARRAY['INFRASTRUCTURE'::character varying, 'DATA_QUALITY'::character varying, 'BUSINESS_RULE'::character varying, 'CONFIG'::character varying, 'UPSTREAM_DELAY'::character varying, 'TIMEOUT'::character varying, 'UNKNOWN'::character varying])))),
    CONSTRAINT ck_job_task_partition_reference_nonzero CHECK ((job_partition_id IS DISTINCT FROM 0)),
    CONSTRAINT ck_job_task_priority CHECK (((priority >= 0) AND (priority <= 10))),
    CONSTRAINT ck_job_task_process_staging_generation_pair CHECK (((process_staging_generation_id IS NULL) = (process_staging_batch_key IS NULL))),
    CONSTRAINT ck_job_task_seq CHECK ((task_seq > 0)),
    CONSTRAINT ck_job_task_status CHECK (((task_status)::text = ANY (ARRAY['CREATED'::character varying, 'READY'::character varying, 'RUNNING'::character varying, 'SUCCESS'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying, 'TERMINATED'::character varying]))),
    CONSTRAINT ck_job_task_type CHECK (((task_type)::text = ANY (ARRAY['EXECUTION'::character varying, 'COMPENSATION'::character varying, 'REPLAY'::character varying, 'IMPORT'::character varying, 'EXPORT'::character varying, 'PROCESS'::character varying, 'DISPATCH'::character varying, 'GENERAL'::character varying, 'WORKFLOW'::character varying, 'ATOMIC'::character varying]))),
    CONSTRAINT job_task_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.job_task IS '发送给 worker 的任务记录；CLAIM、执行、续租和结果回报的权威状态机。';


COMMENT ON COLUMN batch.job_task.task_type IS '任务类型；决定目标 worker 和执行适配器。';


COMMENT ON COLUMN batch.job_task.task_status IS '任务状态机；CLAIM、租约、执行回报均以此为准。';


COMMENT ON COLUMN batch.job_task.assigned_worker_code IS '当前持有任务租约的 worker 编码。';


COMMENT ON COLUMN batch.job_task.task_payload IS '投递给 worker 的任务载荷 JSON；敏感信息必须使用引用而非明文。';


COMMENT ON COLUMN batch.job_task.result_summary IS 'worker 回报的结果摘要 JSON。';


COMMENT ON COLUMN batch.job_task.error_code IS '标准化错误码。';


COMMENT ON COLUMN batch.job_task.error_message IS '可展示的错误摘要；敏感内容必须脱敏。';


COMMENT ON COLUMN batch.job_task.version IS 'CAS 乐观锁版本。';


COMMENT ON COLUMN batch.job_task.error_key IS 'i18n message key;读路径按当前 Locale 重渲染替代 error_message';


COMMENT ON COLUMN batch.job_task.error_args IS 'i18n key 的占位符参数 JSON 数组,与 messages.properties {0}/{1}/... 对应';


COMMENT ON COLUMN batch.job_task.priority IS 'V88 (2026-05-03): 拷自 job_definition.priority, 数值越大越优先 (默认 5, 范围 0-10)';


COMMENT ON COLUMN batch.job_task.failure_class IS '失败分类；用于类别化重试和告警。';


COMMENT ON COLUMN batch.job_task.dry_run IS 'ADR-026 dry-run 演练；从父 partition.dry_run 透传，与父值一致';


COMMENT ON COLUMN batch.job_task.effective_parameters IS '执行前解析后的有效参数 JSON；覆盖定义默认值但不保存明文密钥。';


COMMENT ON COLUMN batch.job_task.heartbeat_details IS 'worker 心跳诊断 JSON；用于租约和故障排查。';


COMMENT ON COLUMN batch.job_task.heartbeat_at IS 'ORCH-P4-1 最近一次带 details 的心跳时间（UTC）';


COMMENT ON COLUMN batch.job_task.cancel_requested IS '控制面请求取消的标记；worker 心跳或执行边界应消费。';


COMMENT ON COLUMN batch.job_task.task_timeout_seconds IS 'ORCH-P4-2 派单期拷自 workflow_node.task_timeout_seconds 的运行态快照；TaskTimeoutEnforcer 据此软取消';


COMMENT ON COLUMN batch.job_task.process_staging_generation_id IS '当前 PROCESS 暂存代次；仅在执行 COMPUTE 时轮换，检查点恢复时保持不变。';


COMMENT ON COLUMN batch.job_task.process_staging_batch_key IS '与 process_staging_generation_id 配对的精确 PROCESS batch_key，用于清理和审计。';


COMMENT ON COLUMN batch.job_task.job_partition_ref_key IS '内部空值安全的分区引用键；0 表示 NULL，不是有效的任务分区 ID。';


COMMENT ON COLUMN batch.job_task.job_instance_biz_date IS '从所属 JobInstance 复制的业务日分区键，用于由数据库原生外键保障租户隔离。';


COMMENT ON COLUMN batch.job_task.non_idempotent_started_at IS '首次持久记录此任务已启动非幂等执行器；租约过期后状态转为 EXTERNAL_OUTCOME_UNKNOWN，避免自动重放。';


COMMENT ON COLUMN batch.job_task.external_outcome_resolution_version IS '经人工核实未执行的 EXTERNAL_OUTCOME_UNKNOWN 任务版本；每次失败尝试仅授权一次。';


COMMENT ON COLUMN batch.job_task.external_outcome_resolution_trigger_request_id IS '获批后唯一可消费该未执行核实授权的根 Workflow 启动请求。';


COMMENT ON COLUMN batch.job_task.external_outcome_resolution_consumed_at IS 'Worker 开始唯一获批外部重试的消费时间；即使 Worker 随后崩溃也不恢复授权。';


COMMENT ON COLUMN batch.job_task.execution_attempt_id IS '当前任务认领内已确认的执行尝试标识；重新认领时清空';


CREATE SEQUENCE batch.job_task_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.job_task_id_seq OWNED BY batch.job_task.id;


CREATE TABLE archive.job_task_archive (
    id bigint DEFAULT nextval('batch.job_task_id_seq'::regclass) NOT NULL,
    tenant_id character varying(64) NOT NULL,
    job_instance_id bigint NOT NULL,
    job_partition_id bigint,
    task_type character varying(32) DEFAULT 'EXECUTION'::character varying NOT NULL,
    task_seq integer DEFAULT 1 NOT NULL,
    task_status character varying(32) NOT NULL,
    assigned_worker_code character varying(128),
    task_payload jsonb,
    result_summary jsonb,
    error_code character varying(64),
    error_message character varying(2048),
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    error_key character varying(128),
    error_args jsonb,
    priority integer,
    failure_class character varying(32),
    dry_run boolean DEFAULT false NOT NULL,
    effective_parameters jsonb,
    heartbeat_details jsonb,
    heartbeat_at timestamp with time zone,
    cancel_requested boolean DEFAULT false NOT NULL,
    task_timeout_seconds integer,
    process_staging_generation_id uuid,
    process_staging_batch_key text,
    job_partition_ref_key bigint GENERATED ALWAYS AS (COALESCE(job_partition_id, (0)::bigint)) STORED,
    job_instance_biz_date date NOT NULL,
    non_idempotent_started_at timestamp with time zone,
    external_outcome_resolution_version bigint,
    external_outcome_resolution_trigger_request_id bigint,
    external_outcome_resolution_consumed_at timestamp with time zone,
    execution_attempt_id character varying(64),
    CONSTRAINT ck_job_task_archive_process_staging_generation_pair CHECK (((process_staging_generation_id IS NULL) = (process_staging_batch_key IS NULL))),
    CONSTRAINT ck_job_task_seq CHECK ((task_seq > 0)),
    CONSTRAINT ck_job_task_status CHECK (((task_status)::text = ANY (ARRAY['CREATED'::character varying, 'READY'::character varying, 'RUNNING'::character varying, 'SUCCESS'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying, 'TERMINATED'::character varying]))),
    CONSTRAINT ck_job_task_type CHECK (((task_type)::text = ANY (ARRAY['EXECUTION'::character varying, 'COMPENSATION'::character varying, 'REPLAY'::character varying, 'IMPORT'::character varying, 'EXPORT'::character varying, 'PROCESS'::character varying, 'DISPATCH'::character varying, 'GENERAL'::character varying, 'WORKFLOW'::character varying, 'ATOMIC'::character varying]))),
    CONSTRAINT pk_job_task_archive PRIMARY KEY (id)
);


COMMENT ON TABLE archive.job_task_archive IS 'batch.job_task 的冷表镜像；保留任务执行证据。';


COMMENT ON COLUMN archive.job_task_archive.error_key IS '镜像 batch.job_task.error_key';


COMMENT ON COLUMN archive.job_task_archive.error_args IS '镜像 batch.job_task.error_args';


COMMENT ON COLUMN archive.job_task_archive.priority IS 'V88 镜像列, 默认从 batch.job_task 拷, 用于历史 task 的优先级回溯';


COMMENT ON COLUMN archive.job_task_archive.process_staging_generation_id IS '任务归档时最后一次 PROCESS 暂存代次。';


COMMENT ON COLUMN archive.job_task_archive.process_staging_batch_key IS '与归档暂存代次配对的精确 PROCESS batch_key。';


COMMENT ON COLUMN archive.job_task_archive.job_partition_ref_key IS '生成的 Task 分区引用身份的冷归档镜像。';


COMMENT ON COLUMN archive.job_task_archive.job_instance_biz_date IS '归档 Task 时保留所属 JobInstance 的业务日身份。';


COMMENT ON COLUMN archive.job_task_archive.non_idempotent_started_at IS 'batch.job_task.non_idempotent_started_at 的归档镜像，用于保留任务执行历史。';


COMMENT ON COLUMN archive.job_task_archive.external_outcome_resolution_version IS '归档的任务版本；其 EXTERNAL_OUTCOME_UNKNOWN 已经人工核实为未执行。';


COMMENT ON COLUMN archive.job_task_archive.external_outcome_resolution_trigger_request_id IS '获批后唯一可消费该未执行核实授权的根 Workflow 启动请求。';


COMMENT ON COLUMN archive.job_task_archive.external_outcome_resolution_consumed_at IS 'Worker 开始唯一获批外部重试的消费时间；即使 Worker 随后崩溃也不恢复授权。';


COMMENT ON COLUMN archive.job_task_archive.execution_attempt_id IS '归档任务最终认领的执行尝试标识';


CREATE TABLE archive.notification_delivery_log_archive (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    rule_id bigint NOT NULL,
    channel_code character varying(64) NOT NULL,
    event_type character varying(64) NOT NULL,
    alert_event_id bigint,
    payload_json jsonb NOT NULL,
    delivery_status character varying(32) DEFAULT 'PENDING'::character varying NOT NULL,
    error_message character varying(1024),
    attempt integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    error_key character varying(128),
    error_args jsonb,
    terminal_event_key character varying(256),
    next_retry_at timestamp with time zone,
    delivery_claim_token uuid,
    delivery_claim_until timestamp with time zone,
    CONSTRAINT ck_notification_delivery_status CHECK (((delivery_status)::text = ANY (ARRAY['PENDING'::character varying, 'SUCCESS'::character varying, 'FAILED'::character varying, 'EXHAUSTED'::character varying]))),
    CONSTRAINT notification_delivery_log_archive_pkey PRIMARY KEY (id)
);

ALTER TABLE ONLY archive.notification_delivery_log_archive FORCE ROW LEVEL SECURITY;


COMMENT ON TABLE archive.notification_delivery_log_archive IS 'Workflow 终态订阅规则投递冷存；保留原结果与人工恢复身份。';


COMMENT ON COLUMN archive.notification_delivery_log_archive.event_type IS '通知对应的业务事件类型。';


COMMENT ON COLUMN archive.notification_delivery_log_archive.payload_json IS '通知渲染载荷 JSON。';


COMMENT ON COLUMN archive.notification_delivery_log_archive.delivery_status IS '通知投递状态。';


COMMENT ON COLUMN archive.notification_delivery_log_archive.error_key IS '同 pipeline_step_run.error_key';


COMMENT ON COLUMN archive.notification_delivery_log_archive.error_args IS '同 pipeline_step_run.error_args';


COMMENT ON COLUMN archive.notification_delivery_log_archive.terminal_event_key IS '可靠 Workflow 终态投递的稳定事件身份。';


COMMENT ON COLUMN archive.notification_delivery_log_archive.next_retry_at IS '可靠终态通知下次投递时间；空值表示无需自动投递。';


COMMENT ON COLUMN archive.notification_delivery_log_archive.delivery_claim_token IS '本轮投递认领 token，回写必须匹配。';


COMMENT ON COLUMN archive.notification_delivery_log_archive.delivery_claim_until IS '认领租约截止时间，过期可恢复。';


CREATE TABLE batch.outbox_event (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    aggregate_type character varying(64) NOT NULL,
    aggregate_id bigint NOT NULL,
    event_type character varying(64) NOT NULL,
    event_key character varying(256) NOT NULL,
    payload_json jsonb NOT NULL,
    publish_status character varying(32) DEFAULT 'NEW'::character varying NOT NULL,
    publish_attempt integer DEFAULT 0 NOT NULL,
    next_publish_at timestamp with time zone,
    trace_id character varying(128),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    priority integer DEFAULT 5 NOT NULL,
    CONSTRAINT ck_outbox_p_priority CHECK (((priority >= 0) AND (priority <= 10))),
    CONSTRAINT ck_outbox_p_publish_attempt CHECK ((publish_attempt >= 0)),
    CONSTRAINT ck_outbox_p_publish_status CHECK (((publish_status)::text = ANY (ARRAY['NEW'::character varying, 'PUBLISHING'::character varying, 'PUBLISHED'::character varying, 'FAILED'::character varying, 'GIVE_UP'::character varying]))),
    CONSTRAINT outbox_event_p_pkey PRIMARY KEY (id, created_at)
)
PARTITION BY RANGE (created_at);


COMMENT ON TABLE batch.outbox_event IS '平台事务 Outbox；与业务状态同事务写入，由发布器异步投递 Kafka。';


COMMENT ON COLUMN batch.outbox_event.aggregate_type IS '产生事件的聚合类型。';


COMMENT ON COLUMN batch.outbox_event.aggregate_id IS '产生事件的聚合主键。';


COMMENT ON COLUMN batch.outbox_event.event_type IS '领域事件类型；决定 Topic 和消费者处理器。';


COMMENT ON COLUMN batch.outbox_event.event_key IS '事件全局幂等键；同租户只允许发布一次。';


COMMENT ON COLUMN batch.outbox_event.payload_json IS '事件载荷 JSON；消费者契约的版本化输入。';


COMMENT ON COLUMN batch.outbox_event.publish_status IS '发布状态机；PENDING、PUBLISHED、FAILED 等由发布器推进。';


COMMENT ON COLUMN batch.outbox_event.trace_id IS '产生事件的端到端链路标识。';


COMMENT ON COLUMN batch.outbox_event.priority IS '发布优先级；数值越大越早投递。';


CREATE SEQUENCE batch.outbox_event_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.outbox_event_id_seq OWNED BY batch.outbox_event.id;


CREATE TABLE archive.outbox_event_archive (
    id bigint DEFAULT nextval('batch.outbox_event_id_seq'::regclass) NOT NULL,
    tenant_id character varying(64) NOT NULL,
    aggregate_type character varying(64) NOT NULL,
    aggregate_id bigint NOT NULL,
    event_type character varying(64) NOT NULL,
    event_key character varying(256) NOT NULL,
    payload_json jsonb NOT NULL,
    publish_status character varying(32) DEFAULT 'NEW'::character varying NOT NULL,
    publish_attempt integer DEFAULT 0 NOT NULL,
    next_publish_at timestamp with time zone,
    trace_id character varying(128),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    priority integer,
    CONSTRAINT ck_outbox_publish_attempt CHECK ((publish_attempt >= 0)),
    CONSTRAINT ck_outbox_publish_status CHECK (((publish_status)::text = ANY (ARRAY['NEW'::character varying, 'PUBLISHING'::character varying, 'PUBLISHED'::character varying, 'FAILED'::character varying, 'GIVE_UP'::character varying]))),
    CONSTRAINT pk_outbox_event_archive PRIMARY KEY (id)
);


COMMENT ON TABLE archive.outbox_event_archive IS 'batch.outbox_event 的冷表镜像；保留事件投递证据。';


COMMENT ON COLUMN archive.outbox_event_archive.priority IS 'V88 镜像列, 默认从 batch.outbox_event 拷, 用于历史投递事件的优先级回溯';


CREATE TABLE batch.pipeline_instance (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    job_code character varying(128) NOT NULL,
    pipeline_type character varying(32) NOT NULL,
    file_id bigint,
    related_job_instance_id bigint,
    current_stage character varying(64),
    last_success_stage character varying(64),
    run_status character varying(32) NOT NULL,
    trace_id character varying(128),
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    system_pipeline_code character varying(32),
    system_pipeline_version integer DEFAULT 1,
    CONSTRAINT ck_pipeline_instance_status CHECK (((run_status)::text = ANY (ARRAY['CREATED'::character varying, 'RUNNING'::character varying, 'SUCCESS'::character varying, 'FAILED'::character varying, 'COMPENSATING'::character varying, 'TERMINATED'::character varying]))),
    CONSTRAINT ck_pipeline_instance_system_code_present CHECK ((system_pipeline_code IS NOT NULL)),
    CONSTRAINT ck_pipeline_instance_system_version CHECK (((system_pipeline_version IS NOT NULL) AND (system_pipeline_version > 0))),
    CONSTRAINT ck_pipeline_instance_type CHECK (((pipeline_type)::text = ANY (ARRAY['IMPORT'::character varying, 'EXPORT'::character varying, 'PROCESS'::character varying, 'DISPATCH'::character varying]))),
    CONSTRAINT pipeline_instance_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.pipeline_instance IS '一次文件流水线运行实例；关联 file_record 和处理状态。';


COMMENT ON COLUMN batch.pipeline_instance.pipeline_type IS 'Pipeline 运行类型。';


COMMENT ON COLUMN batch.pipeline_instance.run_status IS '流水线实例状态机。';


COMMENT ON COLUMN batch.pipeline_instance.trace_id IS '流水线链路标识。';


COMMENT ON COLUMN batch.pipeline_instance.system_pipeline_code IS '按 Job 类型选定的平台 Pipeline 编码；运行期间不可变。';


COMMENT ON COLUMN batch.pipeline_instance.system_pipeline_version IS '本次运行使用的系统 Pipeline 合同语义版本。';


CREATE SEQUENCE batch.pipeline_instance_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.pipeline_instance_id_seq OWNED BY batch.pipeline_instance.id;


CREATE TABLE archive.pipeline_instance_archive (
    id bigint DEFAULT nextval('batch.pipeline_instance_id_seq'::regclass) NOT NULL,
    tenant_id character varying(64) NOT NULL,
    job_code character varying(128) NOT NULL,
    pipeline_type character varying(32) NOT NULL,
    file_id bigint,
    related_job_instance_id bigint,
    current_stage character varying(64),
    last_success_stage character varying(64),
    run_status character varying(32) NOT NULL,
    trace_id character varying(128),
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    system_pipeline_code character varying(32),
    system_pipeline_version integer DEFAULT 1,
    CONSTRAINT ck_pipeline_instance_archive_system_code_present CHECK ((system_pipeline_code IS NOT NULL)),
    CONSTRAINT ck_pipeline_instance_archive_system_version CHECK (((system_pipeline_version IS NOT NULL) AND (system_pipeline_version > 0))),
    CONSTRAINT ck_pipeline_instance_status CHECK (((run_status)::text = ANY (ARRAY['CREATED'::character varying, 'RUNNING'::character varying, 'SUCCESS'::character varying, 'FAILED'::character varying, 'COMPENSATING'::character varying, 'TERMINATED'::character varying]))),
    CONSTRAINT ck_pipeline_instance_type CHECK (((pipeline_type)::text = ANY (ARRAY['IMPORT'::character varying, 'EXPORT'::character varying, 'PROCESS'::character varying, 'DISPATCH'::character varying]))),
    CONSTRAINT pk_pipeline_instance_archive PRIMARY KEY (id)
);


COMMENT ON TABLE archive.pipeline_instance_archive IS 'batch.pipeline_instance 的冷表镜像。';


CREATE TABLE batch.pipeline_progress (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    pipeline_instance_id bigint NOT NULL,
    stage character varying(32) NOT NULL,
    position_marker character varying(512),
    processed_count bigint DEFAULT 0 NOT NULL,
    completed boolean DEFAULT false NOT NULL,
    completed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_pipeline_progress_processed_count CHECK ((processed_count >= 0)),
    CONSTRAINT ck_pipeline_progress_stage CHECK (((stage)::text = ANY (ARRAY['LOAD'::character varying, 'GENERATE'::character varying]))),
    CONSTRAINT pipeline_progress_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.pipeline_progress IS 'ADR-038 平台 worker 续跑位点:Import LOAD / Export GENERATE 阶段内进度持久化';


COMMENT ON COLUMN batch.pipeline_progress.pipeline_instance_id IS '关联 batch.pipeline_instance.id;一次 pipeline 实例同 stage 只有一行';


COMMENT ON COLUMN batch.pipeline_progress.stage IS 'LOAD(Import) / GENERATE(Export);Atomic / Dispatch / Process 不在此续跑';


COMMENT ON COLUMN batch.pipeline_progress.position_marker IS 'Import=已处理到的行号(字符串化);Export=plugin 的 nextCursor 序列化';


COMMENT ON COLUMN batch.pipeline_progress.processed_count IS '已成功处理记录数(chunk/page 业务写 commit 后随 position_marker 一并 advance;Import 跨库故为补偿式一致,非同事务)';


COMMENT ON COLUMN batch.pipeline_progress.completed IS 'true 表示该 stage 在本 pipeline 实例已整体完成(幂等跳过)';


CREATE SEQUENCE batch.pipeline_progress_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.pipeline_progress_id_seq OWNED BY batch.pipeline_progress.id;


CREATE TABLE archive.pipeline_progress_archive (
    id bigint DEFAULT nextval('batch.pipeline_progress_id_seq'::regclass) NOT NULL,
    tenant_id character varying(64) NOT NULL,
    pipeline_instance_id bigint NOT NULL,
    stage character varying(32) NOT NULL,
    position_marker character varying(512),
    processed_count bigint DEFAULT 0 NOT NULL,
    completed boolean DEFAULT false NOT NULL,
    completed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_pipeline_progress_processed_count CHECK ((processed_count >= 0)),
    CONSTRAINT ck_pipeline_progress_stage CHECK (((stage)::text = ANY (ARRAY['LOAD'::character varying, 'GENERATE'::character varying]))),
    CONSTRAINT pk_pipeline_progress_archive PRIMARY KEY (id)
);


COMMENT ON TABLE archive.pipeline_progress_archive IS 'V164 新增的 batch.pipeline_progress 归档镜像。';


CREATE TABLE batch.pipeline_step_run (
    id bigint NOT NULL,
    pipeline_instance_id bigint NOT NULL,
    step_code character varying(128) NOT NULL,
    stage_code character varying(64) NOT NULL,
    run_seq integer DEFAULT 1 NOT NULL,
    step_status character varying(32) NOT NULL,
    input_summary jsonb,
    output_summary jsonb,
    error_code character varying(64),
    error_message character varying(1024),
    retry_count integer DEFAULT 0 NOT NULL,
    duration_ms bigint DEFAULT 0 NOT NULL,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    error_key character varying(128),
    error_args jsonb,
    CONSTRAINT ck_pipeline_step_run_duration CHECK ((duration_ms >= 0)),
    CONSTRAINT ck_pipeline_step_run_retry_count CHECK ((retry_count >= 0)),
    CONSTRAINT ck_pipeline_step_run_status CHECK (((step_status)::text = ANY (ARRAY['PENDING'::character varying, 'RUNNING'::character varying, 'SUCCESS'::character varying, 'FAILED'::character varying, 'RETRYING'::character varying, 'SKIPPED'::character varying]))),
    CONSTRAINT pipeline_step_run_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.pipeline_step_run IS '流水线步骤运行明细；用于进度、重试和错误诊断。';


COMMENT ON COLUMN batch.pipeline_step_run.step_status IS '步骤运行状态机。';


COMMENT ON COLUMN batch.pipeline_step_run.input_summary IS '步骤输入摘要 JSON。';


COMMENT ON COLUMN batch.pipeline_step_run.output_summary IS '步骤输出摘要 JSON。';


COMMENT ON COLUMN batch.pipeline_step_run.error_code IS '步骤标准化错误码。';


COMMENT ON COLUMN batch.pipeline_step_run.error_message IS '步骤错误摘要。';


COMMENT ON COLUMN batch.pipeline_step_run.retry_count IS 'Pipeline 步骤当前重试次数。';


COMMENT ON COLUMN batch.pipeline_step_run.error_key IS 'i18n message key;读路径按当前 Locale 渲染替代 error_message';


COMMENT ON COLUMN batch.pipeline_step_run.error_args IS 'i18n key 的占位符参数 JSON 数组';


CREATE SEQUENCE batch.pipeline_step_run_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.pipeline_step_run_id_seq OWNED BY batch.pipeline_step_run.id;


CREATE TABLE archive.pipeline_step_run_archive (
    id bigint DEFAULT nextval('batch.pipeline_step_run_id_seq'::regclass) NOT NULL,
    pipeline_instance_id bigint NOT NULL,
    step_code character varying(128) NOT NULL,
    stage_code character varying(64) NOT NULL,
    run_seq integer DEFAULT 1 NOT NULL,
    step_status character varying(32) NOT NULL,
    input_summary jsonb,
    output_summary jsonb,
    error_code character varying(64),
    error_message character varying(1024),
    retry_count integer DEFAULT 0 NOT NULL,
    duration_ms bigint DEFAULT 0 NOT NULL,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    error_key character varying(128),
    error_args jsonb,
    CONSTRAINT ck_pipeline_step_run_duration CHECK ((duration_ms >= 0)),
    CONSTRAINT ck_pipeline_step_run_retry_count CHECK ((retry_count >= 0)),
    CONSTRAINT ck_pipeline_step_run_status CHECK (((step_status)::text = ANY (ARRAY['PENDING'::character varying, 'RUNNING'::character varying, 'SUCCESS'::character varying, 'FAILED'::character varying, 'RETRYING'::character varying, 'SKIPPED'::character varying]))),
    CONSTRAINT pk_pipeline_step_run_archive PRIMARY KEY (id)
);


COMMENT ON TABLE archive.pipeline_step_run_archive IS 'batch.pipeline_step_run 的冷表镜像。';


COMMENT ON COLUMN archive.pipeline_step_run_archive.error_key IS '镜像 batch.pipeline_step_run.error_key';


COMMENT ON COLUMN archive.pipeline_step_run_archive.error_args IS '镜像 batch.pipeline_step_run.error_args';


CREATE TABLE batch.result_version (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    business_key character varying(256) NOT NULL,
    version_no integer NOT NULL,
    job_instance_id bigint NOT NULL,
    status character varying(32) NOT NULL,
    effective_at timestamp with time zone,
    deactivated_at timestamp with time zone,
    payload_storage character varying(32) DEFAULT 'INLINE_JSON'::character varying NOT NULL,
    payload_json jsonb,
    payload_ref character varying(512),
    generated_at timestamp with time zone NOT NULL,
    generated_by character varying(128),
    promotion_policy character varying(32) DEFAULT 'AUTO_LATEST'::character varying NOT NULL,
    approval_id bigint,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    dq_gate_status character varying(16),
    CONSTRAINT ck_result_version_dq_gate_status CHECK (((dq_gate_status IS NULL) OR ((dq_gate_status)::text = ANY (ARRAY['PASS'::character varying, 'WARN'::character varying, 'BLOCKED'::character varying])))),
    CONSTRAINT ck_result_version_payload_storage CHECK (((payload_storage)::text = ANY (ARRAY['INLINE_JSON'::character varying, 'EXTERNAL_REF'::character varying, 'FILE_RECORD'::character varying]))),
    CONSTRAINT ck_result_version_promotion_policy CHECK (((promotion_policy)::text = ANY (ARRAY['AUTO_LATEST'::character varying, 'MANUAL_APPROVAL'::character varying]))),
    CONSTRAINT ck_result_version_status CHECK (((status)::text = ANY (ARRAY['PENDING'::character varying, 'EFFECTIVE'::character varying, 'SUPERSEDED'::character varying, 'ARCHIVED'::character varying, 'DRY_RUN'::character varying]))),
    CONSTRAINT result_version_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.result_version IS '结果版本主模型 (ADR-017): 同 business_key 至多 1 EFFECTIVE; 重跑产生新版本';


COMMENT ON COLUMN batch.result_version.business_key IS '业务主键: job:{jobCode}:{bizDate} / workflow:{wfCode}:{bizDate} / job:{jobCode}:{bizDate}:p={partitionKey}';


COMMENT ON COLUMN batch.result_version.version_no IS '结果版本号。';


COMMENT ON COLUMN batch.result_version.status IS 'PENDING / EFFECTIVE / SUPERSEDED / ARCHIVED — 同 business_key 至多 1 EFFECTIVE';


COMMENT ON COLUMN batch.result_version.payload_storage IS 'INLINE_JSON: payload_json 直接存; EXTERNAL_REF: payload_ref 是 oss://...; FILE_RECORD: payload_ref 是 file_record:{id}';


COMMENT ON COLUMN batch.result_version.payload_json IS '结果版本载荷 JSON。';


COMMENT ON COLUMN batch.result_version.payload_ref IS '结果版本载荷的受控引用。';


COMMENT ON COLUMN batch.result_version.promotion_policy IS 'AUTO_LATEST: 立即 EFFECTIVE; MANUAL_APPROVAL: 创建 PENDING 等审批';


COMMENT ON COLUMN batch.result_version.dq_gate_status IS 'ADR-021 DQ gate 结果：PASS / WARN / BLOCKED；BLOCKED → promotion_policy 强制 MANUAL_APPROVAL';


CREATE SEQUENCE batch.result_version_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.result_version_id_seq OWNED BY batch.result_version.id;


CREATE TABLE archive.result_version_archive (
    id bigint DEFAULT nextval('batch.result_version_id_seq'::regclass) NOT NULL,
    tenant_id character varying(64) NOT NULL,
    business_key character varying(256) NOT NULL,
    version_no integer NOT NULL,
    job_instance_id bigint NOT NULL,
    status character varying(32) NOT NULL,
    effective_at timestamp with time zone,
    deactivated_at timestamp with time zone,
    payload_storage character varying(32) DEFAULT 'INLINE_JSON'::character varying NOT NULL,
    payload_json jsonb,
    payload_ref character varying(512),
    generated_at timestamp with time zone NOT NULL,
    generated_by character varying(128),
    promotion_policy character varying(32) DEFAULT 'AUTO_LATEST'::character varying NOT NULL,
    approval_id bigint,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    dq_gate_status character varying(16),
    CONSTRAINT ck_result_version_archive_dq_gate_status CHECK (((dq_gate_status IS NULL) OR ((dq_gate_status)::text = ANY (ARRAY['PASS'::character varying, 'WARN'::character varying, 'BLOCKED'::character varying])))),
    CONSTRAINT ck_result_version_payload_storage CHECK (((payload_storage)::text = ANY (ARRAY['INLINE_JSON'::character varying, 'EXTERNAL_REF'::character varying, 'FILE_RECORD'::character varying]))),
    CONSTRAINT ck_result_version_promotion_policy CHECK (((promotion_policy)::text = ANY (ARRAY['AUTO_LATEST'::character varying, 'MANUAL_APPROVAL'::character varying]))),
    CONSTRAINT result_version_archive_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE archive.result_version_archive IS 'V108 新增的 batch.result_version 归档镜像；记录由保留期调度器在状态为 ARCHIVED 时写入。';


COMMENT ON COLUMN archive.result_version_archive.business_key IS '业务主键: job:{jobCode}:{bizDate} / workflow:{wfCode}:{bizDate} / job:{jobCode}:{bizDate}:p={partitionKey}';


COMMENT ON COLUMN archive.result_version_archive.status IS 'PENDING / EFFECTIVE / SUPERSEDED / ARCHIVED — 同 business_key 至多 1 EFFECTIVE';


COMMENT ON COLUMN archive.result_version_archive.payload_storage IS 'INLINE_JSON: payload_json 直接存; EXTERNAL_REF: payload_ref 是 oss://...; FILE_RECORD: payload_ref 是 file_record:{id}';


COMMENT ON COLUMN archive.result_version_archive.promotion_policy IS 'AUTO_LATEST: 立即 EFFECTIVE; MANUAL_APPROVAL: 创建 PENDING 等审批';


CREATE TABLE batch.trigger_outbox_event (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    request_id character varying(128) NOT NULL,
    topic character varying(128) DEFAULT 'batch.trigger.launch.v1'::character varying NOT NULL,
    payload jsonb NOT NULL,
    publish_status character varying(16) DEFAULT 'NEW'::character varying NOT NULL,
    publish_attempt integer DEFAULT 0 NOT NULL,
    last_error character varying(2048),
    trace_id character varying(128),
    next_publish_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    published_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    publish_claim_token uuid,
    CONSTRAINT ck_trigger_outbox_claim_token CHECK ((((publish_status)::text = 'PUBLISHING'::text) = (publish_claim_token IS NOT NULL))),
    CONSTRAINT ck_trigger_outbox_status CHECK (((publish_status)::text = ANY (ARRAY['NEW'::character varying, 'PUBLISHING'::character varying, 'PUBLISHED'::character varying, 'FAILED'::character varying, 'GIVE_UP'::character varying]))),
    CONSTRAINT trigger_outbox_event_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.trigger_outbox_event IS 'Trigger 事务 Outbox；与 trigger_request 同事务写入，异步通知 Orchestrator。';


COMMENT ON COLUMN batch.trigger_outbox_event.payload IS 'LaunchEnvelope JSON，字段包括 launchRequest、dedupKey、traceId 和 sourceFireTime。';


COMMENT ON COLUMN batch.trigger_outbox_event.publish_status IS 'NEW/PUBLISHING/PUBLISHED/FAILED/GIVE_UP, 对齐 OutboxPublishStatus enum';


COMMENT ON COLUMN batch.trigger_outbox_event.trace_id IS '触发事件链路标识。';


COMMENT ON COLUMN batch.trigger_outbox_event.next_publish_at IS '退避用,最早可被 relay 扫到的时刻';


COMMENT ON COLUMN batch.trigger_outbox_event.publish_claim_token IS '当前 PUBLISHING 领取轮次的 UUID；成功、失败回写须匹配，离开 PUBLISHING 时清空。';


CREATE SEQUENCE batch.trigger_outbox_event_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.trigger_outbox_event_id_seq OWNED BY batch.trigger_outbox_event.id;


CREATE TABLE archive.trigger_outbox_event_archive (
    id bigint DEFAULT nextval('batch.trigger_outbox_event_id_seq'::regclass) NOT NULL,
    tenant_id character varying(64) NOT NULL,
    request_id character varying(128) NOT NULL,
    topic character varying(128) DEFAULT 'batch.trigger.launch.v1'::character varying NOT NULL,
    payload jsonb NOT NULL,
    publish_status character varying(16) DEFAULT 'NEW'::character varying NOT NULL,
    publish_attempt integer DEFAULT 0 NOT NULL,
    last_error character varying(2048),
    trace_id character varying(128),
    next_publish_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    published_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    publish_claim_token uuid,
    CONSTRAINT ck_trigger_outbox_archive_claim_token CHECK ((((publish_status)::text = 'PUBLISHING'::text) = (publish_claim_token IS NOT NULL))),
    CONSTRAINT ck_trigger_outbox_status CHECK (((publish_status)::text = ANY (ARRAY['NEW'::character varying, 'PUBLISHING'::character varying, 'PUBLISHED'::character varying, 'FAILED'::character varying, 'GIVE_UP'::character varying]))),
    CONSTRAINT pk_trigger_outbox_event_archive PRIMARY KEY (id)
);


COMMENT ON TABLE archive.trigger_outbox_event_archive IS 'batch.trigger_outbox_event 的冷表镜像。';


CREATE TABLE archive.webhook_delivery_log_archive (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    subscription_id bigint NOT NULL,
    event_type character varying(64) NOT NULL,
    payload_json jsonb NOT NULL,
    http_status integer,
    response_body character varying(2048),
    delivery_status character varying(32) DEFAULT 'PENDING'::character varying NOT NULL,
    attempt integer DEFAULT 0 NOT NULL,
    next_retry_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    terminal_event_key character varying(256),
    delivery_claim_token uuid,
    delivery_claim_until timestamp with time zone,
    CONSTRAINT ck_webhook_delivery_status CHECK (((delivery_status)::text = ANY (ARRAY['PENDING'::character varying, 'SUCCESS'::character varying, 'FAILED'::character varying, 'EXHAUSTED'::character varying, 'GIVE_UP'::character varying]))),
    CONSTRAINT webhook_delivery_log_archive_pkey PRIMARY KEY (id)
);

ALTER TABLE ONLY archive.webhook_delivery_log_archive FORCE ROW LEVEL SECURITY;


COMMENT ON TABLE archive.webhook_delivery_log_archive IS 'Workflow 终态 Webhook 投递冷存；仅收纳已闭合通知族。';


COMMENT ON COLUMN archive.webhook_delivery_log_archive.event_type IS 'Webhook 对应的业务事件类型。';


COMMENT ON COLUMN archive.webhook_delivery_log_archive.payload_json IS 'Webhook 请求载荷 JSON。';


COMMENT ON COLUMN archive.webhook_delivery_log_archive.http_status IS '下游 HTTP 响应状态码。';


COMMENT ON COLUMN archive.webhook_delivery_log_archive.delivery_status IS 'Webhook 投递状态。';


COMMENT ON COLUMN archive.webhook_delivery_log_archive.next_retry_at IS '下一次 Webhook 重试时点。';


COMMENT ON COLUMN archive.webhook_delivery_log_archive.terminal_event_key IS '可靠 Workflow 终态投递的稳定事件身份。';


COMMENT ON COLUMN archive.webhook_delivery_log_archive.delivery_claim_token IS '本轮投递认领 token，回写必须匹配。';


COMMENT ON COLUMN archive.webhook_delivery_log_archive.delivery_claim_until IS '认领租约截止时间，过期可恢复。';


CREATE TABLE batch.workflow_node_run (
    id bigint NOT NULL,
    workflow_run_id bigint NOT NULL,
    node_code character varying(128) NOT NULL,
    node_type character varying(32) NOT NULL,
    run_seq integer DEFAULT 1 NOT NULL,
    node_status character varying(32) NOT NULL,
    retry_count integer DEFAULT 0 NOT NULL,
    error_code character varying(64),
    error_message character varying(1024),
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    duration_ms bigint DEFAULT 0 NOT NULL,
    output jsonb,
    error_key character varying(128),
    error_args jsonb,
    sensor_next_probe_at timestamp with time zone,
    sensor_last_probe_at timestamp with time zone,
    sensor_probe_count integer DEFAULT 0 NOT NULL,
    sensor_error_count integer DEFAULT 0 NOT NULL,
    job_instance_id bigint,
    skip_reason_code character varying(64),
    skip_reason character varying(1024),
    source_payload_protected jsonb,
    CONSTRAINT ck_workflow_node_run_duration CHECK ((duration_ms >= 0)),
    CONSTRAINT ck_workflow_node_run_retry CHECK ((retry_count >= 0)),
    CONSTRAINT ck_workflow_node_run_status CHECK (((node_status)::text = ANY (ARRAY['READY'::character varying, 'WAITING_DEPENDENCY'::character varying, 'RUNNING'::character varying, 'SUCCESS'::character varying, 'FAILED'::character varying, 'SKIPPED'::character varying]))),
    CONSTRAINT ck_workflow_node_run_type CHECK (((node_type)::text = ANY (ARRAY['TASK'::character varying, 'GATEWAY'::character varying, 'START'::character varying, 'END'::character varying, 'WAIT'::character varying, 'JOB'::character varying]))),
    CONSTRAINT workflow_node_run_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.workflow_node_run IS '工作流节点一次运行明细；承载节点状态、重试、超时和输出。';


COMMENT ON COLUMN batch.workflow_node_run.node_type IS '节点执行类型。';


COMMENT ON COLUMN batch.workflow_node_run.node_status IS '节点运行状态机。';


COMMENT ON COLUMN batch.workflow_node_run.retry_count IS '节点已重试次数。';


COMMENT ON COLUMN batch.workflow_node_run.error_code IS '节点标准化错误码。';


COMMENT ON COLUMN batch.workflow_node_run.error_message IS '节点错误摘要。';


COMMENT ON COLUMN batch.workflow_node_run.output IS '节点结构化输出 JSON；供后续节点引用。';


COMMENT ON COLUMN batch.workflow_node_run.error_key IS '同 job_task.error_key';


COMMENT ON COLUMN batch.workflow_node_run.error_args IS '同 job_task.error_args';


COMMENT ON COLUMN batch.workflow_node_run.sensor_next_probe_at IS 'ADR-028 WAIT 节点下次轮询时间；NULL 表示 RUNNING 后首次探测立刻执行';


COMMENT ON COLUMN batch.workflow_node_run.sensor_last_probe_at IS 'ADR-028 上次探测时间（成功/失败均更新）';


COMMENT ON COLUMN batch.workflow_node_run.sensor_probe_count IS 'ADR-028 累计探测次数';


COMMENT ON COLUMN batch.workflow_node_run.sensor_error_count IS 'ADR-028 连续 ERROR 次数；达 3 次推进 FAILED';


COMMENT ON COLUMN batch.workflow_node_run.job_instance_id IS '执行此 Workflow JOB 节点的 JobInstance；历史未关联节点及非 JOB 节点为空。';


COMMENT ON COLUMN batch.workflow_node_run.skip_reason_code IS 'Workflow 节点被 SKIPPED 的稳定原因类别，例如 CONDITION_NOT_MATCHED 或 OPERATOR_SKIPPED。';


COMMENT ON COLUMN batch.workflow_node_run.skip_reason IS '操作员填写的可读原因，或 Workflow 节点被跳过的简要说明。';


COMMENT ON COLUMN batch.workflow_node_run.source_payload_protected IS '经 KMS 保护的源载荷快照，用于恢复跨业务日依赖等待。';


CREATE SEQUENCE batch.workflow_node_run_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.workflow_node_run_id_seq OWNED BY batch.workflow_node_run.id;


CREATE TABLE archive.workflow_node_run_archive (
    id bigint DEFAULT nextval('batch.workflow_node_run_id_seq'::regclass) NOT NULL,
    workflow_run_id bigint NOT NULL,
    node_code character varying(128) NOT NULL,
    node_type character varying(32) NOT NULL,
    run_seq integer DEFAULT 1 NOT NULL,
    node_status character varying(32) NOT NULL,
    retry_count integer DEFAULT 0 NOT NULL,
    error_code character varying(64),
    error_message character varying(1024),
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    duration_ms bigint DEFAULT 0 NOT NULL,
    output jsonb,
    error_key character varying(128),
    error_args jsonb,
    sensor_next_probe_at timestamp with time zone,
    sensor_last_probe_at timestamp with time zone,
    sensor_probe_count integer DEFAULT 0 NOT NULL,
    sensor_error_count integer DEFAULT 0 NOT NULL,
    job_instance_id bigint,
    skip_reason_code character varying(64),
    skip_reason character varying(1024),
    source_payload_protected jsonb,
    CONSTRAINT ck_workflow_node_run_duration CHECK ((duration_ms >= 0)),
    CONSTRAINT ck_workflow_node_run_retry CHECK ((retry_count >= 0)),
    CONSTRAINT ck_workflow_node_run_status CHECK (((node_status)::text = ANY (ARRAY['READY'::character varying, 'RUNNING'::character varying, 'SUCCESS'::character varying, 'FAILED'::character varying, 'SKIPPED'::character varying]))),
    CONSTRAINT ck_workflow_node_run_type CHECK (((node_type)::text = ANY (ARRAY['TASK'::character varying, 'GATEWAY'::character varying, 'FILE_STEP'::character varying, 'START'::character varying, 'END'::character varying, 'JOB'::character varying]))),
    CONSTRAINT pk_workflow_node_run_archive PRIMARY KEY (id)
);


COMMENT ON TABLE archive.workflow_node_run_archive IS 'batch.workflow_node_run 的冷表镜像。';


COMMENT ON COLUMN archive.workflow_node_run_archive.output IS 'ADR-009: 同 batch.workflow_node_run.output，归档时直接复制';


COMMENT ON COLUMN archive.workflow_node_run_archive.error_key IS '镜像 batch.workflow_node_run.error_key';


COMMENT ON COLUMN archive.workflow_node_run_archive.error_args IS '镜像 batch.workflow_node_run.error_args';


COMMENT ON COLUMN archive.workflow_node_run_archive.job_instance_id IS 'batch.workflow_node_run.job_instance_id 的归档镜像；未记录 JobInstance 关联时为空。';


COMMENT ON COLUMN archive.workflow_node_run_archive.source_payload_protected IS '经 KMS 保护的源载荷快照，用于恢复跨业务日依赖等待。';


CREATE TABLE batch.workflow_run (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    workflow_definition_id bigint NOT NULL,
    related_job_instance_id bigint,
    biz_date date,
    run_status character varying(32) NOT NULL,
    current_node_code character varying(128),
    trace_id character varying(128),
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    dry_run boolean DEFAULT false NOT NULL,
    single_job_definition_id bigint,
    trigger_request_id bigint,
    workflow_version_snapshot integer,
    data_interval_start timestamp with time zone,
    data_interval_end timestamp with time zone,
    execution_snapshot jsonb,
    active_slot smallint DEFAULT 1 NOT NULL,
    CONSTRAINT ck_workflow_run_active_slot CHECK (((active_slot >= 1) AND (active_slot <= 64))),
    CONSTRAINT ck_workflow_run_data_interval CHECK (((data_interval_start IS NULL) OR (data_interval_end IS NULL) OR (data_interval_start < data_interval_end))),
    CONSTRAINT ck_workflow_run_status CHECK (((run_status)::text = ANY (ARRAY['CREATED'::character varying, 'RUNNING'::character varying, 'PAUSED'::character varying, 'SUCCESS'::character varying, 'FAILED'::character varying, 'TERMINATED'::character varying, 'SUCCESS_DRY_RUN'::character varying, 'FAILED_DRY_RUN'::character varying]))),
    CONSTRAINT workflow_run_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.workflow_run IS '工作流一次运行的聚合状态；关联触发的 job_instance 并推进节点。';


COMMENT ON COLUMN batch.workflow_run.related_job_instance_id IS '触发该工作流的作业实例标识。';


COMMENT ON COLUMN batch.workflow_run.run_status IS '工作流运行状态机。';


COMMENT ON COLUMN batch.workflow_run.current_node_code IS '当前推进或等待的节点编码。';


COMMENT ON COLUMN batch.workflow_run.trace_id IS '工作流链路标识。';


COMMENT ON COLUMN batch.workflow_run.dry_run IS 'ADR-026 dry-run 演练标记；与 batch_day_instance.dry_run / job_instance.dry_run 必须一致';


COMMENT ON COLUMN batch.workflow_run.single_job_definition_id IS '启动时冻结的单作业 Workflow 关联；组合 Workflow 和历史运行时为空。';


COMMENT ON COLUMN batch.workflow_run.trigger_request_id IS '创建此 WorkflowRun 的 TriggerRequest；在租户内唯一，用于保证 Kafka 重投幂等。';


COMMENT ON COLUMN batch.workflow_run.workflow_version_snapshot IS '本次运行受理时冻结的 Workflow 定义版本。';


COMMENT ON COLUMN batch.workflow_run.data_interval_start IS '此 WorkflowRun 冻结的触发数据区间起点，包含该时刻。';


COMMENT ON COLUMN batch.workflow_run.data_interval_end IS '此 WorkflowRun 冻结的触发数据区间终点，不包含该时刻。';


COMMENT ON COLUMN batch.workflow_run.execution_snapshot IS 'WorkflowRun 受理时冻结的不可变 DAG 和节点有效执行配置。';


COMMENT ON COLUMN batch.workflow_run.active_slot IS '同租户/Workflow/业务日/模式下的活跃槽位（1..max_active_runs_per_biz_date）；终态后不再占用唯一约束。';


CREATE SEQUENCE batch.workflow_run_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.workflow_run_id_seq OWNED BY batch.workflow_run.id;


CREATE TABLE archive.workflow_run_archive (
    id bigint DEFAULT nextval('batch.workflow_run_id_seq'::regclass) NOT NULL,
    tenant_id character varying(64) NOT NULL,
    workflow_definition_id bigint NOT NULL,
    related_job_instance_id bigint,
    biz_date date,
    run_status character varying(32) NOT NULL,
    current_node_code character varying(128),
    trace_id character varying(128),
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    dry_run boolean DEFAULT false NOT NULL,
    single_job_definition_id bigint,
    trigger_request_id bigint,
    workflow_version_snapshot integer,
    data_interval_start timestamp with time zone,
    data_interval_end timestamp with time zone,
    execution_snapshot jsonb,
    active_slot smallint DEFAULT 1 NOT NULL,
    CONSTRAINT ck_workflow_run_status CHECK (((run_status)::text = ANY (ARRAY['CREATED'::character varying, 'RUNNING'::character varying, 'PAUSED'::character varying, 'SUCCESS'::character varying, 'FAILED'::character varying, 'TERMINATED'::character varying, 'SUCCESS_DRY_RUN'::character varying, 'FAILED_DRY_RUN'::character varying]))),
    CONSTRAINT pk_workflow_run_archive PRIMARY KEY (id)
);


COMMENT ON TABLE archive.workflow_run_archive IS 'batch.workflow_run 的冷表镜像。';


CREATE TABLE archive.workflow_terminal_notification_receipt_archive (
    tenant_id character varying(64) NOT NULL,
    event_key character varying(256) NOT NULL,
    payload_json jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT workflow_terminal_notification_receipt_archive_pkey PRIMARY KEY (tenant_id, event_key)
);

ALTER TABLE ONLY archive.workflow_terminal_notification_receipt_archive FORCE ROW LEVEL SECURITY;


COMMENT ON TABLE archive.workflow_terminal_notification_receipt_archive IS 'Workflow 终态消费回执冷存；永久保留已消费身份，迟到事件不能重新展开投递。';


COMMENT ON COLUMN archive.workflow_terminal_notification_receipt_archive.tenant_id IS '事件所属租户。';


COMMENT ON COLUMN archive.workflow_terminal_notification_receipt_archive.event_key IS '稳定的租户/WorkflowRun/terminal 事件身份。';


COMMENT ON COLUMN archive.workflow_terminal_notification_receipt_archive.payload_json IS '首次消费的不可变终态载荷。';


COMMENT ON COLUMN archive.workflow_terminal_notification_receipt_archive.created_at IS '首次消费提交时间。';


CREATE TABLE batch.alert_escalation_notification_outbox (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    alert_event_id bigint NOT NULL,
    escalation_tier integer NOT NULL,
    stream character varying(64) NOT NULL,
    event_type character varying(64) NOT NULL,
    payload_json jsonb NOT NULL,
    publish_status character varying(32) DEFAULT 'NEW'::character varying NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    next_publish_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    last_error character varying(1024),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    claim_token uuid,
    CONSTRAINT ck_alert_escalation_notification_outbox_attempt CHECK ((attempt_count >= 0)),
    CONSTRAINT ck_alert_escalation_notification_outbox_status CHECK (((publish_status)::text = ANY (ARRAY['NEW'::character varying, 'PUBLISHING'::character varying, 'PUBLISHED'::character varying, 'FAILED'::character varying, 'GIVE_UP'::character varying]))),
    CONSTRAINT ck_alert_escalation_notification_outbox_tier CHECK ((escalation_tier > 0)),
    CONSTRAINT alert_escalation_notification_outbox_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.alert_escalation_notification_outbox IS '告警升级通知 Outbox；与 escalation_notified_tier 水位线同事务写入，再异步投递 Console 通知链路。';


COMMENT ON COLUMN batch.alert_escalation_notification_outbox.alert_event_id IS '对应 batch.alert_event.id。';


COMMENT ON COLUMN batch.alert_escalation_notification_outbox.escalation_tier IS '本次已获得 CAS 所有权的升级层级。';


COMMENT ON COLUMN batch.alert_escalation_notification_outbox.event_type IS '投递到 Console 通知链路的事件类型，当前为 ALERT_ESCALATED。';


COMMENT ON COLUMN batch.alert_escalation_notification_outbox.payload_json IS 'ALERT_ESCALATED 通知载荷 JSON。';


COMMENT ON COLUMN batch.alert_escalation_notification_outbox.publish_status IS '发布状态机：NEW、PUBLISHING、PUBLISHED、FAILED、GIVE_UP。';


COMMENT ON COLUMN batch.alert_escalation_notification_outbox.claim_token IS '本轮发布者凭据；领取时生成，状态复位与成功/失败/放弃回写必须匹配。';


CREATE SEQUENCE batch.alert_escalation_notification_outbox_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.alert_escalation_notification_outbox_id_seq OWNED BY batch.alert_escalation_notification_outbox.id;


CREATE TABLE batch.alert_event (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    service_name character varying(64) NOT NULL,
    alert_type character varying(64) NOT NULL,
    severity character varying(16) NOT NULL,
    title character varying(512) NOT NULL,
    detail_json jsonb,
    dedup_fingerprint character varying(128) NOT NULL,
    occurrence_count integer DEFAULT 1 NOT NULL,
    first_seen_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    last_seen_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    trace_id character varying(128),
    status character varying(32) DEFAULT 'OPEN'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    escalation_tier integer DEFAULT 0 NOT NULL,
    escalated_at timestamp with time zone,
    escalation_notified_tier integer DEFAULT 0 NOT NULL,
    closed_at timestamp with time zone,
    close_reason character varying(32),
    am_last_reemit_at timestamp with time zone,
    CONSTRAINT ck_alert_event_occurrence CHECK ((occurrence_count > 0)),
    CONSTRAINT ck_alert_event_severity CHECK (((severity)::text = ANY (ARRAY['INFO'::character varying, 'WARN'::character varying, 'ERROR'::character varying, 'CRITICAL'::character varying]))),
    CONSTRAINT ck_alert_event_status CHECK (((status)::text = ANY (ARRAY['OPEN'::character varying, 'ACKED'::character varying, 'SUPPRESSED'::character varying, 'CLOSED'::character varying]))),
    CONSTRAINT alert_event_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.alert_event IS '告警事件权威记录；去重、升级、路由和通知均以此为输入。';


COMMENT ON COLUMN batch.alert_event.alert_type IS '告警类型。';


COMMENT ON COLUMN batch.alert_event.detail_json IS '告警详情 JSON。';


COMMENT ON COLUMN batch.alert_event.dedup_fingerprint IS '告警去重指纹。';


COMMENT ON COLUMN batch.alert_event.trace_id IS '关联运行链路标识。';


COMMENT ON COLUMN batch.alert_event.status IS '告警生命周期状态。';


COMMENT ON COLUMN batch.alert_event.closed_at IS '本次关闭意图的数据库时刻；历史空值不自动制造恢复通知。';


COMMENT ON COLUMN batch.alert_event.close_reason IS '关闭原因：MANUAL 为人工关闭，RECOVERED 为当前违约恢复，POLICY_DISABLED 为规则主动撤销。';


COMMENT ON COLUMN batch.alert_event.am_last_reemit_at IS '最近一次周期重发尝试时刻，仅用于公平轮询，不表示最终渠道送达。';


CREATE SEQUENCE batch.alert_event_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.alert_event_id_seq OWNED BY batch.alert_event.id;


CREATE TABLE batch.alert_notification_receipt (
    tenant_id character varying(64) NOT NULL,
    dedup_fingerprint character varying(128) NOT NULL,
    receiver_code character varying(128) NOT NULL,
    firing_delivered_at timestamp with time zone,
    resolved_delivered_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_alert_notification_receipt_delivered CHECK (((firing_delivered_at IS NOT NULL) OR (resolved_delivered_at IS NOT NULL))),
    CONSTRAINT pk_alert_notification_receipt PRIMARY KEY (tenant_id, dedup_fingerprint, receiver_code)
);

ALTER TABLE ONLY batch.alert_notification_receipt FORCE ROW LEVEL SECURITY;


COMMENT ON TABLE batch.alert_notification_receipt IS '告警最终通知成功回执；保留每个实体和接收渠道的发生/恢复证据，跟随原告警删除。';


COMMENT ON COLUMN batch.alert_notification_receipt.tenant_id IS '通知所属租户，与原告警复合外键绑定。';


COMMENT ON COLUMN batch.alert_notification_receipt.dedup_fingerprint IS '原告警的稳定去重指纹，不使用 Alertmanager 自身的指纹替代。';


COMMENT ON COLUMN batch.alert_notification_receipt.receiver_code IS '已成功投递的 Alertmanager 接收渠道。';


COMMENT ON COLUMN batch.alert_notification_receipt.firing_delivered_at IS '发生通知首次明确成功的数据库时刻，空表示尚无送达证据。';


COMMENT ON COLUMN batch.alert_notification_receipt.resolved_delivered_at IS '恢复通知最近一次明确成功的数据库时刻，与发生回执分别保存。';


COMMENT ON COLUMN batch.alert_notification_receipt.created_at IS '首条成功回执的写入时刻。';


COMMENT ON COLUMN batch.alert_notification_receipt.updated_at IS '最近成功回执的写入时刻。';


CREATE TABLE batch.alert_routing_config (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    route_code character varying(128) NOT NULL,
    route_name character varying(256) NOT NULL,
    team character varying(128) NOT NULL,
    alert_group character varying(128) NOT NULL,
    severity character varying(16) NOT NULL,
    receiver character varying(256) NOT NULL,
    group_by character varying(512),
    group_wait_seconds integer DEFAULT 30 NOT NULL,
    group_interval_seconds integer DEFAULT 300 NOT NULL,
    repeat_interval_seconds integer DEFAULT 3600 NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    description character varying(1024),
    created_by character varying(64),
    updated_by character varying(64),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    is_deleted boolean DEFAULT false NOT NULL,
    CONSTRAINT ck_alert_routing_severity CHECK (((severity)::text = ANY (ARRAY['INFO'::character varying, 'WARN'::character varying, 'ERROR'::character varying, 'CRITICAL'::character varying]))),
    CONSTRAINT alert_routing_config_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.alert_routing_config IS '告警路由和聚合配置；决定接收者、静默与重复通知。';


COMMENT ON COLUMN batch.alert_routing_config.group_interval_seconds IS '同组告警聚合窗口秒数。';


COMMENT ON COLUMN batch.alert_routing_config.repeat_interval_seconds IS '未恢复告警重复通知间隔秒数。';


COMMENT ON COLUMN batch.alert_routing_config.is_deleted IS '软删除标记(同上); recreate 同 route_code 走 upsert 自动复活';


CREATE SEQUENCE batch.alert_routing_config_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.alert_routing_config_id_seq OWNED BY batch.alert_routing_config.id;


CREATE TABLE batch.api_key (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    key_name character varying(128) NOT NULL,
    key_prefix character varying(8) NOT NULL,
    key_hash character varying(256) NOT NULL,
    scopes character varying(512) DEFAULT '*'::character varying NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    expires_at timestamp with time zone,
    last_used_at timestamp with time zone,
    created_by character varying(64),
    revoked_by character varying(64),
    revoked_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    salt character varying(64),
    key_hash_algo character varying(16) DEFAULT 'sha256'::character varying NOT NULL,
    CONSTRAINT api_key_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.api_key IS '内部或外部 API Key 元数据；仅保存哈希、盐和作用域。';


COMMENT ON COLUMN batch.api_key.key_prefix IS '仅用于识别的 Key 前缀，不可用于认证。';


COMMENT ON COLUMN batch.api_key.key_hash IS '不可逆 API Key 哈希。';


COMMENT ON COLUMN batch.api_key.scopes IS 'API Key 授权作用域集合。';


COMMENT ON COLUMN batch.api_key.salt IS '派生 Key 哈希的随机盐；遗留 sha256 行可为空。';


COMMENT ON COLUMN batch.api_key.key_hash_algo IS 'Key 哈希算法版本。';


CREATE SEQUENCE batch.api_key_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.api_key_id_seq OWNED BY batch.api_key.id;


CREATE TABLE batch.approval_command (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    approval_no character varying(128) NOT NULL,
    approval_type character varying(64) NOT NULL,
    action_type character varying(64) NOT NULL,
    target_type character varying(64) NOT NULL,
    target_id character varying(128),
    payload_json jsonb NOT NULL,
    approval_status character varying(32) NOT NULL,
    requester_id character varying(64),
    approver_id character varying(64),
    rejection_reason character varying(1024),
    approval_reason character varying(1024),
    source_trace_id character varying(128),
    source_idempotency_key character varying(128),
    approved_at timestamp with time zone,
    executed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    source_approval_reason character varying(1024),
    CONSTRAINT ck_approval_command_action CHECK (((action_type)::text = ANY (ARRAY['CATCH_UP'::character varying, 'COMPENSATION'::character varying, 'DLQ_REPLAY'::character varying, 'DOWNLOAD'::character varying, 'RETRY'::character varying, 'RERUN'::character varying, 'WORKFLOW_RUN_REPLAY'::character varying]))),
    CONSTRAINT ck_approval_command_status CHECK (((approval_status)::text = ANY (ARRAY['PENDING'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying, 'EXECUTED'::character varying]))),
    CONSTRAINT ck_approval_command_type CHECK (((approval_type)::text = ANY (ARRAY['CATCH_UP'::character varying, 'COMPENSATION'::character varying, 'DLQ_REPLAY'::character varying, 'DOWNLOAD'::character varying, 'SELF_SERVICE'::character varying]))),
    CONSTRAINT approval_command_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.approval_command IS '高风险操作审批命令；审批结果驱动后续执行。';


COMMENT ON COLUMN batch.approval_command.approval_type IS '审批流程类型。';


COMMENT ON COLUMN batch.approval_command.action_type IS '待审批操作类型。';


COMMENT ON COLUMN batch.approval_command.target_type IS '审批目标对象类型。';


COMMENT ON COLUMN batch.approval_command.payload_json IS '待审批操作的受控载荷 JSON。';


COMMENT ON COLUMN batch.approval_command.approval_status IS '审批状态机。';


COMMENT ON COLUMN batch.approval_command.source_trace_id IS '发起审批的来源链路标识。';


COMMENT ON COLUMN batch.approval_command.source_idempotency_key IS '来源操作幂等键。';


COMMENT ON COLUMN batch.approval_command.source_approval_reason IS '提交审批时冻结的原始原因，用于来源幂等键及恢复回执核对。';


COMMENT ON CONSTRAINT ck_approval_command_action ON batch.approval_command IS '限制审批命令可执行的业务动作类型。';


CREATE SEQUENCE batch.approval_command_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.approval_command_id_seq OWNED BY batch.approval_command.id;


CREATE TABLE batch.archive_policy (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    target_table character varying(64) NOT NULL,
    retention_days integer DEFAULT 90 NOT NULL,
    archive_enabled boolean DEFAULT false NOT NULL,
    cleanup_enabled boolean DEFAULT false NOT NULL,
    batch_size integer DEFAULT 1000 NOT NULL,
    description character varying(512),
    created_by character varying(64),
    updated_by character varying(64),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_archive_policy_table CHECK (((target_table)::text = ANY (ARRAY['job_instance'::character varying, 'workflow_run'::character varying, 'job_partition'::character varying, 'file_record'::character varying, 'audit_log'::character varying, 'outbox_event'::character varying, 'event_delivery_log'::character varying, 'webhook_delivery_log'::character varying, 'console_operation_audit'::character varying, 'trigger_outbox_event'::character varying, 'dead_letter_task'::character varying, 'job_execution_log'::character varying]))),
    CONSTRAINT archive_policy_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.archive_policy IS '热表归档策略；定义目标表、保留期和启用状态。';


COMMENT ON COLUMN batch.archive_policy.target_table IS '归档目标表全限定引用。';


COMMENT ON COLUMN batch.archive_policy.retention_days IS '热表数据保留天数。';


COMMENT ON COLUMN batch.archive_policy.archive_enabled IS '是否启用该归档策略。';


CREATE SEQUENCE batch.archive_policy_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.archive_policy_id_seq OWNED BY batch.archive_policy.id;


CREATE TABLE batch.asset_freshness_policy (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    asset_code character varying(128) NOT NULL,
    asset_type character varying(32) DEFAULT 'JOB'::character varying NOT NULL,
    expected_by_local_time time without time zone NOT NULL,
    timezone character varying(64) DEFAULT 'UTC'::character varying NOT NULL,
    stale_after_seconds integer DEFAULT 0 NOT NULL,
    lookback_days integer DEFAULT 1 NOT NULL,
    severity character varying(16) DEFAULT 'WARN'::character varying NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_asset_freshness_policy_lookback CHECK (((lookback_days >= 1) AND (lookback_days <= 31))),
    CONSTRAINT ck_asset_freshness_policy_severity CHECK (((severity)::text = ANY (ARRAY['INFO'::character varying, 'WARN'::character varying, 'ERROR'::character varying, 'CRITICAL'::character varying]))),
    CONSTRAINT ck_asset_freshness_policy_stale_after CHECK ((stale_after_seconds >= 0)),
    CONSTRAINT ck_asset_freshness_policy_type CHECK (((asset_type)::text = 'JOB'::text)),
    CONSTRAINT asset_freshness_policy_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.asset_freshness_policy IS 'BFS JOB asset freshness SLA 策略;用于生成 missing/stale alert_event,不参与 readiness 放行裁决';


COMMENT ON COLUMN batch.asset_freshness_policy.asset_code IS 'JOB 类型下等于 job_code';


COMMENT ON COLUMN batch.asset_freshness_policy.asset_type IS '资产对象类型。';


COMMENT ON COLUMN batch.asset_freshness_policy.expected_by_local_time IS '每天该业务日结果应生效的本地时间';


COMMENT ON COLUMN batch.asset_freshness_policy.timezone IS '新鲜度判断所用 IANA 时区。';


COMMENT ON COLUMN batch.asset_freshness_policy.stale_after_seconds IS 'expected_by_local_time 之后的宽限秒数;超出后告警从 MISSING 升为 STALE';


COMMENT ON COLUMN batch.asset_freshness_policy.lookback_days IS '每轮向前扫描的业务日天数,包含当前本地日期';


CREATE SEQUENCE batch.asset_freshness_policy_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.asset_freshness_policy_id_seq OWNED BY batch.asset_freshness_policy.id;


CREATE TABLE batch.asset_partition (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    asset_id bigint NOT NULL,
    asset_code character varying(128) NOT NULL,
    partition_key character varying(256) NOT NULL,
    biz_date date NOT NULL,
    freshness_status character varying(32) NOT NULL,
    result_version_id bigint,
    business_key character varying(256) NOT NULL,
    job_instance_id bigint,
    effective_at timestamp with time zone,
    payload_storage character varying(32),
    payload_ref character varying(512),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_asset_partition_freshness_status CHECK (((freshness_status)::text = 'EFFECTIVE'::text)),
    CONSTRAINT asset_partition_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.asset_partition IS 'BFS 最小资产分区新鲜度读模型;由 EFFECTIVE result_version 物化刷新';


COMMENT ON COLUMN batch.asset_partition.partition_key IS '分区键;JOB 类型第一阶段固定为 biz_date ISO 字符串';


COMMENT ON COLUMN batch.asset_partition.freshness_status IS '新鲜度状态;当前只物化 EFFECTIVE,非生效版本不写入本表';


COMMENT ON COLUMN batch.asset_partition.result_version_id IS '当前分区对应的 EFFECTIVE result_version.id';


COMMENT ON COLUMN batch.asset_partition.payload_storage IS '资产分区载荷存储介质标识。';


COMMENT ON COLUMN batch.asset_partition.payload_ref IS '资产分区载荷的受控引用。';


CREATE SEQUENCE batch.asset_partition_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.asset_partition_id_seq OWNED BY batch.asset_partition.id;


CREATE TABLE batch.atomic_task_config (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    task_type character varying(64) NOT NULL,
    name character varying(128) NOT NULL,
    parameters jsonb NOT NULL,
    created_by character varying(128),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT atomic_task_config_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.atomic_task_config IS 'R3-5 / Round-1 TOP-8 — 租户保存的 atomic 节点(sql/shell/stored_proc/http)配置;FE 2-B 编辑器拉取后挂 workflow_node';


COMMENT ON COLUMN batch.atomic_task_config.task_type IS '内置原子 taskType:sql / stored_proc / shell / http(与 ConsoleAtomicTaskTypeSchemaService.CATALOG 对齐)';


COMMENT ON COLUMN batch.atomic_task_config.name IS '租户视角的配置名(同租户同 task_type 内唯一)';


COMMENT ON COLUMN batch.atomic_task_config.parameters IS 'parameters JSONB,key 必须落 schema.parameters[].name 集合,凭据字段被 SensitiveDataValidator 拒入';


COMMENT ON COLUMN batch.atomic_task_config.created_by IS '创建人 console 账号(UPDATE 不改,仅 INSERT 期间写入)';


CREATE SEQUENCE batch.atomic_task_config_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.atomic_task_config_id_seq OWNED BY batch.atomic_task_config.id;


CREATE TABLE batch.batch_day_catchup_control (
    tenant_id character varying(64) NOT NULL,
    calendar_code character varying(128) NOT NULL,
    paused boolean DEFAULT false NOT NULL,
    paused_at timestamp with time zone,
    paused_by character varying(128),
    pause_reason character varying(1024),
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_batch_day_catchup_control_state CHECK (((paused AND (paused_at IS NOT NULL) AND (paused_by IS NOT NULL) AND (pause_reason IS NOT NULL)) OR (NOT paused))),
    CONSTRAINT pk_batch_day_catchup_control PRIMARY KEY (tenant_id, calendar_code)
);

ALTER TABLE ONLY batch.batch_day_catchup_control FORCE ROW LEVEL SECURITY;


COMMENT ON TABLE batch.batch_day_catchup_control IS '每租户/日历的停机补齐暂停状态；只暂停历史 catch-up，不影响当前业务日正常开启';


CREATE TABLE batch.batch_day_catchup_control_event (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    calendar_code character varying(128) NOT NULL,
    action character varying(16) NOT NULL,
    operator_id character varying(128) NOT NULL,
    reason character varying(1024) NOT NULL,
    occurred_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_batch_day_catchup_control_event_action CHECK (((action)::text = ANY (ARRAY['PAUSE'::character varying, 'RESUME'::character varying]))),
    CONSTRAINT batch_day_catchup_control_event_pkey PRIMARY KEY (id)
);

ALTER TABLE ONLY batch.batch_day_catchup_control_event FORCE ROW LEVEL SECURITY;


COMMENT ON TABLE batch.batch_day_catchup_control_event IS '日切补齐暂停/恢复的操作者和原因审计历史';


CREATE SEQUENCE batch.batch_day_catchup_control_event_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.batch_day_catchup_control_event_id_seq OWNED BY batch.batch_day_catchup_control_event.id;


CREATE TABLE batch.batch_day_catchup_operation (
    tenant_id character varying(64) NOT NULL,
    request_key_hash character varying(64) NOT NULL,
    request_hash character varying(64) NOT NULL,
    request_json jsonb NOT NULL,
    snapshot_json jsonb NOT NULL,
    items_json jsonb NOT NULL,
    operation_status character varying(16) NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_batch_day_catchup_items_array CHECK ((jsonb_typeof(items_json) = 'array'::text)),
    CONSTRAINT ck_batch_day_catchup_key_hash CHECK (((request_key_hash)::text ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT ck_batch_day_catchup_request_hash CHECK (((request_hash)::text ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT ck_batch_day_catchup_request_object CHECK ((jsonb_typeof(request_json) = 'object'::text)),
    CONSTRAINT ck_batch_day_catchup_revision CHECK ((revision >= 0)),
    CONSTRAINT ck_batch_day_catchup_snapshot_object CHECK ((jsonb_typeof(snapshot_json) = 'object'::text)),
    CONSTRAINT ck_batch_day_catchup_status CHECK (((operation_status)::text = ANY (ARRAY['PREPARED'::character varying, 'RUNNING'::character varying, 'COMPLETE'::character varying, 'UNKNOWN'::character varying, 'ABANDONED'::character varying]))),
    CONSTRAINT batch_day_catchup_operation_pkey PRIMARY KEY (tenant_id, request_key_hash)
);

ALTER TABLE ONLY batch.batch_day_catchup_operation FORCE ROW LEVEL SECURITY;


COMMENT ON TABLE batch.batch_day_catchup_operation IS '批量日补跑原请求、冻结候选与逐项对账状态；结果不明时不得据当前候选重新提交。';


COMMENT ON COLUMN batch.batch_day_catchup_operation.tenant_id IS '发起请求的租户，也是行级隔离边界。';


COMMENT ON COLUMN batch.batch_day_catchup_operation.request_key_hash IS '原请求幂等键的不可逆摘要，租户内唯一。';


COMMENT ON COLUMN batch.batch_day_catchup_operation.request_hash IS '规范化原请求的摘要，用于拒绝同键不同载荷。';


COMMENT ON COLUMN batch.batch_day_catchup_operation.request_json IS '规范化原请求；仅保存恢复所需的信息。';


COMMENT ON COLUMN batch.batch_day_catchup_operation.snapshot_json IS '首次受理时冻结的业务日、日历、策略和候选上下文。';


COMMENT ON COLUMN batch.batch_day_catchup_operation.items_json IS '冻结的逐项身份和已确认结果；不明结果不能视为未提交。';


COMMENT ON COLUMN batch.batch_day_catchup_operation.operation_status IS 'PREPARED、RUNNING、COMPLETE、UNKNOWN 或未建档原请求已围栏的 ABANDONED。';


COMMENT ON COLUMN batch.batch_day_catchup_operation.revision IS '并发更新使用的版本围栏。';


COMMENT ON COLUMN batch.batch_day_catchup_operation.created_at IS '原请求记录创建时间。';


COMMENT ON COLUMN batch.batch_day_catchup_operation.updated_at IS '请求或对账状态最后更新时间。';


CREATE TABLE batch.batch_day_instance (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    calendar_code character varying(128) NOT NULL,
    biz_date date NOT NULL,
    day_status character varying(32) DEFAULT 'OPEN'::character varying NOT NULL,
    open_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    cutoff_at timestamp with time zone,
    settled_at timestamp with time zone,
    sla_deadline_at timestamp with time zone,
    late_count integer DEFAULT 0 NOT NULL,
    catchup_count integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    timezone_snapshot character varying(64) DEFAULT 'UTC'::character varying NOT NULL,
    frozen boolean DEFAULT false NOT NULL,
    operation_reason character varying(256),
    operated_by character varying(128),
    operated_at timestamp with time zone,
    dst_policy_snapshot character varying(96) DEFAULT 'gap=RUN_AT_NEXT_VALID_TIME;overlap=RUN_ONCE_EARLIER_OFFSET'::character varying NOT NULL,
    dry_run boolean DEFAULT false NOT NULL,
    CONSTRAINT ck_batch_day_instance_catchup_count CHECK ((catchup_count >= 0)),
    CONSTRAINT ck_batch_day_instance_late_count CHECK ((late_count >= 0)),
    CONSTRAINT ck_batch_day_instance_status CHECK (((day_status)::text = ANY (ARRAY['OPEN'::character varying, 'CUTOFF'::character varying, 'IN_FLIGHT'::character varying, 'SETTLING'::character varying, 'SETTLED'::character varying, 'FAILED'::character varying, 'SKIPPED'::character varying, 'MANUAL_RELEASED'::character varying]))),
    CONSTRAINT batch_day_instance_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.batch_day_instance IS '租户日历批量日实例；批量日状态和跨日依赖裁决的权威来源。';


COMMENT ON COLUMN batch.batch_day_instance.day_status IS '批量日状态机。';


COMMENT ON COLUMN batch.batch_day_instance.version IS '批量日 CAS 乐观锁版本。';


COMMENT ON COLUMN batch.batch_day_instance.timezone_snapshot IS '创建时冻结的日历时区。';


COMMENT ON COLUMN batch.batch_day_instance.frozen IS '人工治理冻结标记；批量日冻结期间，cutoff 和 settle 调度器会跳过该日，直至解冻、重新打开或关闭。';


COMMENT ON COLUMN batch.batch_day_instance.operation_reason IS '最近一次人工治理操作的原因码或自由文本原因。';


COMMENT ON COLUMN batch.batch_day_instance.operated_by IS '最近一次人工治理操作的操作者 ID。';


COMMENT ON COLUMN batch.batch_day_instance.operated_at IS '最近一次人工治理操作的时间。';


COMMENT ON COLUMN batch.batch_day_instance.dst_policy_snapshot IS '创建时冻结的 DST 处理策略。';


COMMENT ON COLUMN batch.batch_day_instance.dry_run IS 'ADR-026 dry-run 整日演练标记';


CREATE SEQUENCE batch.batch_day_instance_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.batch_day_instance_id_seq OWNED BY batch.batch_day_instance.id;


CREATE TABLE batch.batch_day_open_cursor (
    tenant_id character varying(64) NOT NULL,
    calendar_code character varying(128) NOT NULL,
    last_scanned_biz_date date NOT NULL,
    last_scan_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_batch_day_open_cursor PRIMARY KEY (tenant_id, calendar_code)
);

ALTER TABLE ONLY batch.batch_day_open_cursor FORCE ROW LEVEL SECURITY;


COMMENT ON TABLE batch.batch_day_open_cursor IS '每租户/日历的原始业务日扫描位置，和批量日创建同事务推进，支持停机恢复及节假日滚动续扫';


COMMENT ON COLUMN batch.batch_day_open_cursor.last_scanned_biz_date IS '已评估的最后一个原始业务日；与节假日滚动后的有效 biz_date 分开，避免滚动目标提前导致漏扫';


CREATE TABLE batch.batch_day_operation_audit (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    calendar_code character varying(128) NOT NULL,
    biz_date date NOT NULL,
    operation_type character varying(64) NOT NULL,
    from_status character varying(32),
    to_status character varying(32),
    from_frozen boolean,
    to_frozen boolean,
    operator_id character varying(128) NOT NULL,
    operator_type character varying(32) NOT NULL,
    reason_code character varying(128),
    comment character varying(1024),
    approval_id bigint,
    request_payload jsonb,
    trace_id character varying(128),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT batch_day_operation_audit_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.batch_day_operation_audit IS '批量日维度高风险治理操作审计 — Console 操作历史与运维查询的权威源';


COMMENT ON COLUMN batch.batch_day_operation_audit.operation_type IS 'BATCH_DAY_FREEZE / RELEASE / SKIP / REOPEN / CLOSE / OPEN / CUTOFF / CATCH_UP 等';


COMMENT ON COLUMN batch.batch_day_operation_audit.from_status IS '批量日操作前状态。';


COMMENT ON COLUMN batch.batch_day_operation_audit.to_status IS '批量日操作后状态。';


COMMENT ON COLUMN batch.batch_day_operation_audit.operator_type IS 'REQUEST(API/Console) / SYSTEM(scheduler/触发兜底)';


COMMENT ON COLUMN batch.batch_day_operation_audit.request_payload IS '操作请求快照 (JSONB) — 用于排障/重放';


COMMENT ON COLUMN batch.batch_day_operation_audit.trace_id IS '批量日操作链路标识。';


CREATE SEQUENCE batch.batch_day_operation_audit_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.batch_day_operation_audit_id_seq OWNED BY batch.batch_day_operation_audit.id;


CREATE TABLE batch.batch_day_replay_preview_token (
    tenant_id character varying(64) NOT NULL,
    token_hash character varying(64) NOT NULL,
    request_hash character varying(64) NOT NULL,
    snapshot_hash character varying(64) NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    consumed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    session_id bigint,
    CONSTRAINT ck_replay_preview_token_hashes CHECK ((((token_hash)::text ~ '^[0-9a-f]{64}$'::text) AND ((request_hash)::text ~ '^[0-9a-f]{64}$'::text) AND ((snapshot_hash)::text ~ '^[0-9a-f]{64}$'::text))),
    CONSTRAINT batch_day_replay_preview_token_pkey PRIMARY KEY (tenant_id, token_hash)
);


COMMENT ON TABLE batch.batch_day_replay_preview_token IS '批次日重放短时预览凭证；保存租户绑定的请求与候选影响快照哈希，提交时原子单次消费。';


COMMENT ON COLUMN batch.batch_day_replay_preview_token.token_hash IS '一次性预览凭证的 SHA-256，不保存客户端持有的原始凭证。';


COMMENT ON COLUMN batch.batch_day_replay_preview_token.request_hash IS '规范化提交参数的 SHA-256；参数变化后必须重新预览。';


COMMENT ON COLUMN batch.batch_day_replay_preview_token.snapshot_hash IS '预览候选及影响明细的 SHA-256；候选或影响变化后必须重新预览。';


COMMENT ON COLUMN batch.batch_day_replay_preview_token.expires_at IS '预览凭证失效时间；过期凭证不能提交。';


COMMENT ON COLUMN batch.batch_day_replay_preview_token.consumed_at IS '成功创建重放会话时的消费时间；非空凭证不可再次提交。';


COMMENT ON COLUMN batch.batch_day_replay_preview_token.session_id IS '成功消费该预览凭证创建的重放会话；同一请求重试时返回该会话。';


CREATE TABLE batch.batch_day_waiting_launch (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    calendar_code character varying(128) NOT NULL,
    job_code character varying(128) NOT NULL,
    biz_date date NOT NULL,
    request_id character varying(128) NOT NULL,
    trace_id character varying(128),
    trigger_type character varying(32) NOT NULL,
    wait_reason character varying(128) NOT NULL,
    launch_payload jsonb NOT NULL,
    wait_status character varying(32) DEFAULT 'WAITING'::character varying NOT NULL,
    released_at timestamp with time zone,
    released_by character varying(128),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_batch_day_waiting_launch_status CHECK (((wait_status)::text = ANY (ARRAY['WAITING'::character varying, 'RELEASED'::character varying, 'REJECTED'::character varying, 'CANCELLED'::character varying]))),
    CONSTRAINT batch_day_waiting_launch_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.batch_day_waiting_launch IS '受前序批量日闸门阻塞的启动意图；释放前不创建 job_instance。';


COMMENT ON COLUMN batch.batch_day_waiting_launch.trace_id IS '待启动请求的链路标识。';


COMMENT ON COLUMN batch.batch_day_waiting_launch.trigger_type IS '生成待启动请求的触发类型。';


COMMENT ON COLUMN batch.batch_day_waiting_launch.launch_payload IS '待启动实例的参数快照 JSON。';


COMMENT ON COLUMN batch.batch_day_waiting_launch.wait_status IS '等待批量日条件的状态。';


CREATE SEQUENCE batch.batch_day_waiting_launch_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.batch_day_waiting_launch_id_seq OWNED BY batch.batch_day_waiting_launch.id;


CREATE TABLE batch.batch_runtime_default_parameter (
    id bigint NOT NULL,
    module character varying(64) NOT NULL,
    parameter_key character varying(128) NOT NULL,
    default_value character varying(512) NOT NULL,
    value_type character varying(16) NOT NULL,
    unit character varying(32),
    yaml_path character varying(512) NOT NULL,
    env_var character varying(256),
    description text,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_batch_runtime_default_value_type CHECK (((value_type)::text = ANY (ARRAY['INTEGER'::character varying, 'BOOLEAN'::character varying, 'STRING'::character varying, 'MILLIS'::character varying, 'SECONDS'::character varying]))),
    CONSTRAINT batch_runtime_default_parameter_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.batch_runtime_default_parameter IS '运行时默认调优参数目录；需与 docs/architecture/runtime-default-parameters.md 保持一致。';


COMMENT ON COLUMN batch.batch_runtime_default_parameter.value_type IS '运行时默认参数的数据类型。';


CREATE SEQUENCE batch.batch_runtime_default_parameter_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.batch_runtime_default_parameter_id_seq OWNED BY batch.batch_runtime_default_parameter.id;


CREATE TABLE batch.batch_window (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    window_code character varying(128) NOT NULL,
    window_name character varying(256) NOT NULL,
    timezone character varying(64) NOT NULL,
    start_time time without time zone NOT NULL,
    end_time time without time zone NOT NULL,
    end_strategy character varying(32) DEFAULT 'FINISH_RUNNING'::character varying NOT NULL,
    out_of_window_action character varying(32) DEFAULT 'WAIT'::character varying NOT NULL,
    allow_cross_day boolean DEFAULT false NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    description character varying(512),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_batch_window_action CHECK (((out_of_window_action)::text = ANY (ARRAY['WAIT'::character varying, 'FAIL'::character varying]))),
    CONSTRAINT ck_batch_window_end_strategy CHECK (((end_strategy)::text = ANY (ARRAY['STOP'::character varying, 'FINISH_RUNNING'::character varying, 'CONTINUE'::character varying]))),
    CONSTRAINT batch_window_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.batch_window IS '租户作业可运行时间窗口配置；由调度器判定是否允许派发。';


COMMENT ON COLUMN batch.batch_window.window_code IS '批量窗口稳定编码。';


COMMENT ON COLUMN batch.batch_window.window_name IS '批量窗口展示名称。';


COMMENT ON COLUMN batch.batch_window.timezone IS '窗口解释时区。';


COMMENT ON COLUMN batch.batch_window.end_strategy IS '窗口结束处理策略。';


COMMENT ON COLUMN batch.batch_window.out_of_window_action IS '窗口外请求的处置策略。';


CREATE SEQUENCE batch.batch_window_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.batch_window_id_seq OWNED BY batch.batch_window.id;


CREATE TABLE batch.biz_table_schema (
    id bigint NOT NULL,
    schema_name character varying(64) NOT NULL,
    table_name character varying(128) NOT NULL,
    columns jsonb NOT NULL,
    registered_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    last_seen_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT biz_table_schema_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.biz_table_schema IS 'worker 启动时上报业务库 schema；Excel 上传按此校验 targetColumn/columnMappings';


COMMENT ON COLUMN batch.biz_table_schema.columns IS 'jsonb 数组：[{"name":"customer_no","type":"varchar","nullable":false}, ...]';


CREATE SEQUENCE batch.biz_table_schema_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.biz_table_schema_id_seq OWNED BY batch.biz_table_schema.id;


CREATE TABLE batch.business_calendar (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    calendar_code character varying(128) NOT NULL,
    calendar_name character varying(256) NOT NULL,
    timezone character varying(64) NOT NULL,
    holiday_roll_rule character varying(32) DEFAULT 'SKIP'::character varying NOT NULL,
    catch_up_policy character varying(32) DEFAULT 'NONE'::character varying NOT NULL,
    catch_up_max_days integer DEFAULT 0 NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    cutoff_time time without time zone DEFAULT '06:00:00'::time without time zone NOT NULL,
    late_arrival_tolerance_min integer DEFAULT 60 NOT NULL,
    sla_offset_min integer DEFAULT 0 NOT NULL,
    day_rollover_policy character varying(32) DEFAULT 'ALLOW_OVERLAP'::character varying NOT NULL,
    dst_gap_policy character varying(32) DEFAULT 'RUN_AT_NEXT_VALID_TIME'::character varying NOT NULL,
    dst_overlap_policy character varying(32) DEFAULT 'RUN_ONCE_EARLIER_OFFSET'::character varying NOT NULL,
    group_code character varying(128),
    cutoff_schedule jsonb,
    description character varying(512),
    CONSTRAINT ck_business_calendar_catchup_days CHECK ((catch_up_max_days >= 0)),
    CONSTRAINT ck_business_calendar_catchup_policy CHECK (((catch_up_policy)::text = ANY (ARRAY['NONE'::character varying, 'AUTO'::character varying, 'MANUAL_APPROVAL'::character varying]))),
    CONSTRAINT ck_business_calendar_day_rollover_policy CHECK (((day_rollover_policy)::text = ANY (ARRAY['ALLOW_OVERLAP'::character varying, 'WAIT_PREVIOUS_DAY'::character varying, 'REJECT_IF_PREVIOUS_OPEN'::character varying]))),
    CONSTRAINT ck_business_calendar_dst_gap_policy CHECK (((dst_gap_policy)::text = ANY (ARRAY['RUN_AT_NEXT_VALID_TIME'::character varying, 'SKIP'::character varying, 'FAIL_FAST'::character varying]))),
    CONSTRAINT ck_business_calendar_dst_overlap_policy CHECK (((dst_overlap_policy)::text = ANY (ARRAY['RUN_ONCE_EARLIER_OFFSET'::character varying, 'RUN_ONCE_LATER_OFFSET'::character varying, 'RUN_TWICE'::character varying]))),
    CONSTRAINT ck_business_calendar_late_arrival_tolerance_min CHECK ((late_arrival_tolerance_min >= 0)),
    CONSTRAINT ck_business_calendar_roll_rule CHECK (((holiday_roll_rule)::text = ANY (ARRAY['SKIP'::character varying, 'NEXT_WORKDAY'::character varying, 'PREV_WORKDAY'::character varying]))),
    CONSTRAINT ck_business_calendar_sla_offset_min CHECK ((sla_offset_min >= 0)),
    CONSTRAINT business_calendar_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.business_calendar IS '租户业务日历配置；定义时区、截日、节假日和补跑规则。';


COMMENT ON COLUMN batch.business_calendar.timezone IS '日历日期解释所用 IANA 时区。';


COMMENT ON COLUMN batch.business_calendar.holiday_roll_rule IS '节假日遇到工作日的顺延规则。';


COMMENT ON COLUMN batch.business_calendar.catch_up_policy IS '错过批量日后的补跑策略。';


COMMENT ON COLUMN batch.business_calendar.cutoff_time IS '批量日切换时间。在该时间之前触发的批次，biz_date 归属前一个业务日。';


COMMENT ON COLUMN batch.business_calendar.late_arrival_tolerance_min IS 'cutoff 之后的容忍窗口（分钟），用于接收晚到数据。';


COMMENT ON COLUMN batch.business_calendar.sla_offset_min IS '批量日 SLA deadline = cutoff_time + sla_offset_min。';


COMMENT ON COLUMN batch.business_calendar.day_rollover_policy IS '业务日切换策略。';


COMMENT ON COLUMN batch.business_calendar.dst_gap_policy IS 'DST 跳时区间的处理策略。';


COMMENT ON COLUMN batch.business_calendar.dst_overlap_policy IS 'DST 重叠区间的处理策略。';


COMMENT ON COLUMN batch.business_calendar.group_code IS 'ADR-023 加入哪个 calendar_group；NULL = 不加入任何组（行为同前）';


COMMENT ON COLUMN batch.business_calendar.cutoff_schedule IS 'ADR-023 半天 / 多 cutoff schedule，JSONB { "default": "06:00", "overrides": [...] }; NULL = 用 cutoff_time 单值（向后兼容）';


COMMENT ON COLUMN batch.business_calendar.description IS '业务日历说明，供 Console Excel 导入/导出和配置包导入使用。';


CREATE SEQUENCE batch.business_calendar_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.business_calendar_id_seq OWNED BY batch.business_calendar.id;


CREATE TABLE batch.business_shard_catalog (
    placement_key character varying(64) NOT NULL,
    host character varying(255) NOT NULL,
    port integer DEFAULT 5432 NOT NULL,
    db_name character varying(64) DEFAULT 'batch_business'::character varying NOT NULL,
    secret_ref character varying(128),
    pool_max_size integer,
    enabled boolean DEFAULT true NOT NULL,
    description character varying(512),
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_by character varying(128),
    CONSTRAINT business_shard_catalog_pkey PRIMARY KEY (placement_key)
);


COMMENT ON TABLE batch.business_shard_catalog IS 'P2 tenant-routing biz 分片目录:placement key→位置(host/port/db)+状态;只存位置不存账密(secret_ref 仅引用名)。';


COMMENT ON COLUMN batch.business_shard_catalog.secret_ref IS '凭据引用名(secrets/vault 里的 key,如 placement_key);本表不存任何账密明文。';


COMMENT ON COLUMN batch.business_shard_catalog.pool_max_size IS '建议每片连接池上限(供运维参考 / 与 routing.shardMaximumPoolSize 对齐);worker 实际池由其配置构建。';


COMMENT ON COLUMN batch.business_shard_catalog.enabled IS '是否可作为 placement 指派目标;false 的片不进 placement key 白名单(但存量指派不受影响)。';


CREATE TABLE batch.business_tenant_placement (
    tenant_id character varying(64) NOT NULL,
    placement_key character varying(64) NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_by character varying(128),
    CONSTRAINT business_tenant_placement_pkey PRIMARY KEY (tenant_id)
);


COMMENT ON TABLE batch.business_tenant_placement IS 'P2 tenant-routing 租户→biz 分片(placement key)显式映射;表命中优先于 hash 默认;只存 key 不存账密。';


COMMENT ON COLUMN batch.business_tenant_placement.placement_key IS 'biz 分片 key(如 shard-0 / silo-big),与 routing.shards 的 key + resolver 输出对应。';


COMMENT ON COLUMN batch.business_tenant_placement.updated_by IS '最后维护者(运维/租户管理流程标识),便于审计迁片操作。';


CREATE TABLE batch.calendar_dependency (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    upstream_code character varying(128) NOT NULL,
    downstream_code character varying(128) NOT NULL,
    rule character varying(32) DEFAULT 'WAIT_SETTLED'::character varying NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    description character varying(512),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_calendar_dependency_no_self_loop CHECK (((upstream_code)::text <> (downstream_code)::text)),
    CONSTRAINT ck_calendar_dependency_rule CHECK (((rule)::text = ANY (ARRAY['WAIT_SETTLED'::character varying, 'WAIT_CUTOFF'::character varying, 'SAME_DAY_PARALLEL'::character varying]))),
    CONSTRAINT calendar_dependency_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.calendar_dependency IS 'ADR-023 跨 calendar 串联依赖：A SETTLED 才起 B（中港美等跨境联动）';


COMMENT ON COLUMN batch.calendar_dependency.rule IS 'WAIT_SETTLED 表示上游 batch_day_instance.day_status 为 SETTLED。 WAIT_CUTOFF：上游 cutoff_at 已过； SAME_DAY_PARALLEL: 同日并行（占位）';


CREATE SEQUENCE batch.calendar_dependency_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.calendar_dependency_id_seq OWNED BY batch.calendar_dependency.id;


CREATE TABLE batch.calendar_dependency_wait (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    calendar_code character varying(128) NOT NULL,
    biz_date date NOT NULL,
    wait_status character varying(16) DEFAULT 'WAITING'::character varying NOT NULL,
    blocked_reason character varying(512) NOT NULL,
    first_waiting_at timestamp with time zone NOT NULL,
    last_checked_at timestamp with time zone NOT NULL,
    attempt_count integer DEFAULT 1 NOT NULL,
    cutoff_at timestamp with time zone,
    sla_deadline_at timestamp with time zone,
    timezone_snapshot character varying(64) NOT NULL,
    dst_policy_snapshot character varying(128) NOT NULL,
    opened_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    timeout_alerted_at timestamp with time zone,
    dependency_snapshot jsonb,
    manual_action character varying(16),
    manual_operator_id character varying(128),
    manual_reason character varying(1024),
    manual_requested_at timestamp with time zone,
    CONSTRAINT ck_calendar_dependency_wait_attempts CHECK ((attempt_count > 0)),
    CONSTRAINT ck_calendar_dependency_wait_manual_action CHECK (((manual_action IS NULL) OR ((manual_action)::text = ANY (ARRAY['RELEASE'::character varying, 'SKIP'::character varying])))),
    CONSTRAINT ck_calendar_dependency_wait_manual_fields CHECK ((((manual_action IS NULL) AND (manual_operator_id IS NULL) AND (manual_reason IS NULL) AND (manual_requested_at IS NULL)) OR ((manual_action IS NOT NULL) AND (manual_operator_id IS NOT NULL) AND (manual_reason IS NOT NULL) AND (manual_requested_at IS NOT NULL)))),
    CONSTRAINT ck_calendar_dependency_wait_status CHECK (((wait_status)::text = ANY (ARRAY['WAITING'::character varying, 'OPENED'::character varying, 'SKIPPED'::character varying]))),
    CONSTRAINT calendar_dependency_wait_pkey PRIMARY KEY (id)
);

ALTER TABLE ONLY batch.calendar_dependency_wait FORCE ROW LEVEL SECURITY;


COMMENT ON TABLE batch.calendar_dependency_wait IS '跨日历依赖等待事实；保留原业务日、等待原因和尝试记录，供开启调度器重启后重检并恢复';


COMMENT ON COLUMN batch.calendar_dependency_wait.biz_date IS '等待期间固定的有效业务日；重检不按当前时钟重新计算，避免跨 cutoff 丢日';


COMMENT ON COLUMN batch.calendar_dependency_wait.wait_status IS 'WAITING 等待依赖；OPENED 已原子创建批量日；SKIPPED 被活动灾难日 SKIP 覆盖';


COMMENT ON COLUMN batch.calendar_dependency_wait.first_waiting_at IS '首次发现上游未就绪的时刻，用于等待时长和超时告警';


COMMENT ON COLUMN batch.calendar_dependency_wait.attempt_count IS '依赖重检次数；可用于等待时长/重试频率观测，不代表执行尝试';


COMMENT ON COLUMN batch.calendar_dependency_wait.timezone_snapshot IS '进入等待时使用的业务日历时区快照；恢复检查不随配置变化漂移';


COMMENT ON COLUMN batch.calendar_dependency_wait.dst_policy_snapshot IS '进入等待时使用的夏令时策略快照；恢复检查沿用原始时点解释规则';


COMMENT ON COLUMN batch.calendar_dependency_wait.timeout_alerted_at IS '首次超过等待超时并成功认领告警的时刻；CAS 防止周期扫描重复发射同一等待告警';


COMMENT ON COLUMN batch.calendar_dependency_wait.dependency_snapshot IS '首次等待时观察到的上游 calendar 与 rule 快照，仅用于审计；重检按当前启用配置裁决，允许修复配置后自动释放';


COMMENT ON COLUMN batch.calendar_dependency_wait.manual_action IS '管理员请求对该等待日执行 RELEASE（跳过依赖闸门）或 SKIP；由调度器在租户事务内落实';


COMMENT ON COLUMN batch.calendar_dependency_wait.manual_operator_id IS '请求人工依赖处置的 Console 操作人';


COMMENT ON COLUMN batch.calendar_dependency_wait.manual_reason IS '人工释放或跳过的必填原因';


CREATE SEQUENCE batch.calendar_dependency_wait_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.calendar_dependency_wait_id_seq OWNED BY batch.calendar_dependency_wait.id;


CREATE TABLE batch.calendar_group (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    group_code character varying(128) NOT NULL,
    description character varying(512),
    enabled boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT calendar_group_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.calendar_group IS 'ADR-023 calendar 命名空间：多 calendar 共享假日 (calendar_holiday.scope=GROUP) 的归属组';


CREATE SEQUENCE batch.calendar_group_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.calendar_group_id_seq OWNED BY batch.calendar_group.id;


CREATE TABLE batch.calendar_holiday (
    id bigint NOT NULL,
    calendar_id bigint NOT NULL,
    biz_date date NOT NULL,
    day_type character varying(32) NOT NULL,
    holiday_name character varying(128),
    description character varying(512),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    tenant_id character varying(64) NOT NULL,
    scope character varying(32) DEFAULT 'CALENDAR'::character varying NOT NULL,
    group_code character varying(128),
    CONSTRAINT ck_calendar_holiday_day_type CHECK (((day_type)::text = ANY (ARRAY['HOLIDAY'::character varying, 'WORKDAY_OVERRIDE'::character varying]))),
    CONSTRAINT ck_calendar_holiday_group_code_required CHECK ((((scope)::text <> 'GROUP'::text) OR (group_code IS NOT NULL))),
    CONSTRAINT ck_calendar_holiday_scope CHECK (((scope)::text = ANY (ARRAY['CALENDAR'::character varying, 'GROUP'::character varying]))),
    CONSTRAINT calendar_holiday_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.calendar_holiday IS '日历或日历组的节假日明细；批量日计算的输入。';


COMMENT ON COLUMN batch.calendar_holiday.day_type IS '日历日类型，例如工作日或节假日。';


COMMENT ON COLUMN batch.calendar_holiday.tenant_id IS 'V92: 多租隔离硬约束直列 (与 business_calendar.tenant_id 同源, 简化 SELECT 路径)';


COMMENT ON COLUMN batch.calendar_holiday.scope IS 'ADR-023 假日作用域：CALENDAR (单 calendar) / GROUP (整组共享)';


COMMENT ON COLUMN batch.calendar_holiday.group_code IS 'ADR-023 scope=GROUP 时所属 calendar_group.group_code；scope=CALENDAR 时 NULL';


CREATE SEQUENCE batch.calendar_holiday_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.calendar_holiday_id_seq OWNED BY batch.calendar_holiday.id;


CREATE TABLE batch.config_approval (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    release_id bigint NOT NULL,
    approval_status character varying(32) DEFAULT 'PENDING'::character varying NOT NULL,
    requested_by character varying(64) NOT NULL,
    requested_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    reviewed_by character varying(64),
    reviewed_at timestamp with time zone,
    review_comment character varying(1024),
    expired_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_config_approval_status CHECK (((approval_status)::text = ANY (ARRAY['PENDING'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying, 'EXPIRED'::character varying]))),
    CONSTRAINT config_approval_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.config_approval IS '配置发布审批记录。';


COMMENT ON COLUMN batch.config_approval.approval_status IS '配置审批状态。';


CREATE SEQUENCE batch.config_approval_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.config_approval_id_seq OWNED BY batch.config_approval.id;


CREATE TABLE batch.config_batch_operation (
    audit_tenant_id character varying(64) NOT NULL,
    operation_id character varying(36) NOT NULL,
    request_identity_hash character varying(64) NOT NULL,
    operation_kind character varying(32) NOT NULL,
    targets_json jsonb NOT NULL,
    response_metadata_json jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_config_batch_operation_metadata CHECK ((jsonb_typeof(response_metadata_json) = 'object'::text)),
    CONSTRAINT ck_config_batch_operation_targets CHECK ((jsonb_typeof(targets_json) = 'array'::text)),
    CONSTRAINT config_batch_operation_pkey PRIMARY KEY (audit_tenant_id, operation_id)
);

ALTER TABLE ONLY batch.config_batch_operation FORCE ROW LEVEL SECURITY;


COMMENT ON TABLE batch.config_batch_operation IS '配置批量操作的原始请求身份和目标顺序；用于核对原操作，不能根据当前配置重新推导结果。';


COMMENT ON COLUMN batch.config_batch_operation.audit_tenant_id IS '拥有本次操作记录的审计租户，也是记录的行级隔离边界。';


COMMENT ON COLUMN batch.config_batch_operation.operation_id IS '原批量操作标识。';


COMMENT ON COLUMN batch.config_batch_operation.request_identity_hash IS '由原请求租户头、方法、路径和幂等键组成的身份摘要。';


COMMENT ON COLUMN batch.config_batch_operation.operation_kind IS '操作类型，用于区分租户复制及作业包入口。';


COMMENT ON COLUMN batch.config_batch_operation.targets_json IS '原请求目标列表，保留顺序和重复目标。';


COMMENT ON COLUMN batch.config_batch_operation.response_metadata_json IS '重建响应所需的非敏感元数据。';


COMMENT ON COLUMN batch.config_batch_operation.created_at IS '操作登记时间。';


CREATE TABLE batch.config_batch_target_receipt (
    audit_tenant_id character varying(64) NOT NULL,
    operation_id character varying(36) NOT NULL,
    target_ordinal integer NOT NULL,
    tenant_id character varying(64) NOT NULL,
    result_json jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_config_batch_receipt_ordinal CHECK ((target_ordinal >= 0)),
    CONSTRAINT ck_config_batch_receipt_result_tenant CHECK (((jsonb_typeof(result_json) = 'object'::text) AND (result_json ? 'tenantId'::text) AND ((result_json ->> 'tenantId'::text) IS NOT NULL) AND ((result_json ->> 'tenantId'::text) = (tenant_id)::text))),
    CONSTRAINT config_batch_target_receipt_pkey PRIMARY KEY (audit_tenant_id, operation_id, target_ordinal)
);

ALTER TABLE ONLY batch.config_batch_target_receipt FORCE ROW LEVEL SECURITY;


COMMENT ON TABLE batch.config_batch_target_receipt IS '配置批量操作的逐项确认结果；成功回执与目标变更同事务提交。';


COMMENT ON COLUMN batch.config_batch_target_receipt.audit_tenant_id IS '本次操作登记所属的审计租户。';


COMMENT ON COLUMN batch.config_batch_target_receipt.operation_id IS '原批量操作标识。';


COMMENT ON COLUMN batch.config_batch_target_receipt.target_ordinal IS '目标在原请求中的从零开始的位置，重复租户使用不同位置。';


COMMENT ON COLUMN batch.config_batch_target_receipt.tenant_id IS '该项事务所属的目标租户，也是回执行级隔离边界。';


COMMENT ON COLUMN batch.config_batch_target_receipt.result_json IS '已确认的目标原始结果，不根据当前配置反推。';


COMMENT ON COLUMN batch.config_batch_target_receipt.created_at IS '目标结果确认并记录的时间。';


CREATE TABLE batch.config_cache_invalidation_outbox (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    config_type character varying(40) NOT NULL,
    config_code character varying(128) NOT NULL,
    created_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    next_attempt_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    claim_token uuid,
    claim_until timestamp with time zone,
    attempts integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_config_cache_invalidation_attempts CHECK ((attempts >= 0)),
    CONSTRAINT ck_config_cache_invalidation_claim CHECK (((claim_token IS NULL) = (claim_until IS NULL))),
    CONSTRAINT config_cache_invalidation_outbox_pkey PRIMARY KEY (id)
);

ALTER TABLE ONLY batch.config_cache_invalidation_outbox FORCE ROW LEVEL SECURITY;


COMMENT ON TABLE batch.config_cache_invalidation_outbox IS '已提交配置的缓存失效意图；完成投递后删除。';


COMMENT ON COLUMN batch.config_cache_invalidation_outbox.id IS '待投递记录标识，不代表事务提交顺序。';


COMMENT ON COLUMN batch.config_cache_invalidation_outbox.tenant_id IS '失效范围所属租户。';


COMMENT ON COLUMN batch.config_cache_invalidation_outbox.config_type IS '缓存配置类型。';


COMMENT ON COLUMN batch.config_cache_invalidation_outbox.config_code IS '配置编码，星号表示该租户类型的全部编码。';


COMMENT ON COLUMN batch.config_cache_invalidation_outbox.created_at IS '失效意图产生时间。';


COMMENT ON COLUMN batch.config_cache_invalidation_outbox.next_attempt_at IS '允许下一次领取的时间。';


COMMENT ON COLUMN batch.config_cache_invalidation_outbox.claim_token IS '当前领取令牌，用于防止过期领取者确认新任务。';


COMMENT ON COLUMN batch.config_cache_invalidation_outbox.claim_until IS '领取租约截止时间。';


COMMENT ON COLUMN batch.config_cache_invalidation_outbox.attempts IS '已领取次数。';


CREATE SEQUENCE batch.config_cache_invalidation_outbox_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.config_cache_invalidation_outbox_id_seq OWNED BY batch.config_cache_invalidation_outbox.id;


CREATE TABLE batch.config_change_log (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    config_type character varying(32) NOT NULL,
    config_key character varying(128) NOT NULL,
    version_no integer NOT NULL,
    change_action character varying(32) NOT NULL,
    change_result character varying(32) NOT NULL,
    operator_type character varying(32) DEFAULT 'API'::character varying NOT NULL,
    operator_id character varying(64),
    trace_id character varying(128),
    change_summary jsonb,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_config_change_log_action CHECK (((change_action)::text = ANY (ARRAY['CREATE'::character varying, 'PUBLISH'::character varying, 'GRAY'::character varying, 'ROLLBACK'::character varying, 'ROTATE'::character varying, 'SUBMIT_APPROVAL'::character varying, 'APPROVE'::character varying, 'REJECT'::character varying, 'REVOKE'::character varying]))),
    CONSTRAINT ck_config_change_log_result CHECK (((change_result)::text = ANY (ARRAY['SUCCESS'::character varying, 'FAILED'::character varying]))),
    CONSTRAINT config_change_log_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.config_change_log IS '配置变更审计日志。';


COMMENT ON COLUMN batch.config_change_log.config_type IS '变更配置对象类型。';


COMMENT ON COLUMN batch.config_change_log.version_no IS '被变更配置的乐观锁版本。';


COMMENT ON COLUMN batch.config_change_log.change_result IS '配置变更结果。';


COMMENT ON COLUMN batch.config_change_log.operator_type IS '发起变更的操作者类型。';


COMMENT ON COLUMN batch.config_change_log.trace_id IS '变更链路标识。';


CREATE SEQUENCE batch.config_change_log_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.config_change_log_id_seq OWNED BY batch.config_change_log.id;


CREATE TABLE batch.config_release (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    config_type character varying(32) NOT NULL,
    config_key character varying(128) NOT NULL,
    config_name character varying(256) NOT NULL,
    config_status character varying(32) DEFAULT 'DRAFT'::character varying NOT NULL,
    version_no integer DEFAULT 1 NOT NULL,
    gray_scope jsonb,
    config_payload jsonb,
    effective_from_at timestamp with time zone,
    effective_to_at timestamp with time zone,
    published_at timestamp with time zone,
    rolled_back_at timestamp with time zone,
    created_by character varying(64),
    updated_by character varying(64),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_config_release_status CHECK (((config_status)::text = ANY (ARRAY['DRAFT'::character varying, 'PENDING_APPROVAL'::character varying, 'PUBLISHED'::character varying, 'GRAY'::character varying, 'ROLLED_BACK'::character varying]))),
    CONSTRAINT ck_config_release_version CHECK ((version_no > 0)),
    CONSTRAINT config_release_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.config_release IS '可发布配置版本；支持灰度范围和审批流。';


COMMENT ON COLUMN batch.config_release.config_type IS '发布配置对象类型。';


COMMENT ON COLUMN batch.config_release.config_status IS '配置发布状态机。';


COMMENT ON COLUMN batch.config_release.version_no IS '发布配置版本号。';


COMMENT ON COLUMN batch.config_release.gray_scope IS '灰度发布范围。';


COMMENT ON COLUMN batch.config_release.config_payload IS '待发布配置快照 JSON。';


CREATE SEQUENCE batch.config_release_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.config_release_id_seq OWNED BY batch.config_release.id;


CREATE TABLE batch.config_sync_log (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    sync_direction character varying(16) NOT NULL,
    source_env character varying(64) NOT NULL,
    target_env character varying(64) NOT NULL,
    config_types character varying(256) NOT NULL,
    total_items integer DEFAULT 0 NOT NULL,
    success_items integer DEFAULT 0 NOT NULL,
    failed_items integer DEFAULT 0 NOT NULL,
    skipped_items integer DEFAULT 0 NOT NULL,
    sync_status character varying(32) DEFAULT 'RUNNING'::character varying NOT NULL,
    detail_json jsonb,
    operator_id character varying(64),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_config_sync_direction CHECK (((sync_direction)::text = ANY (ARRAY['EXPORT'::character varying, 'IMPORT'::character varying]))),
    CONSTRAINT ck_config_sync_status CHECK (((sync_status)::text = ANY (ARRAY['RUNNING'::character varying, 'SUCCESS'::character varying, 'PARTIAL_FAILED'::character varying, 'FAILED'::character varying]))),
    CONSTRAINT config_sync_log_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.config_sync_log IS '跨环境配置同步执行记录。';


COMMENT ON COLUMN batch.config_sync_log.config_types IS '本次同步的配置类型集合。';


COMMENT ON COLUMN batch.config_sync_log.sync_status IS '配置同步状态。';


COMMENT ON COLUMN batch.config_sync_log.detail_json IS '配置同步结果详情 JSON。';


CREATE SEQUENCE batch.config_sync_log_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.config_sync_log_id_seq OWNED BY batch.config_sync_log.id;


CREATE TABLE batch.config_sync_target_receipt (
    audit_tenant_id character varying(64) NOT NULL,
    log_id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    result_json jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_config_sync_receipt_result_tenant CHECK (((jsonb_typeof(result_json) = 'object'::text) AND (result_json ? 'tenantId'::text) AND ((result_json ->> 'tenantId'::text) IS NOT NULL) AND ((result_json ->> 'tenantId'::text) = (tenant_id)::text))),
    CONSTRAINT config_sync_target_receipt_pkey PRIMARY KEY (audit_tenant_id, log_id, tenant_id)
);

ALTER TABLE ONLY batch.config_sync_target_receipt FORCE ROW LEVEL SECURITY;


COMMENT ON TABLE batch.config_sync_target_receipt IS '配置导入的不可变目标提交结果；成功回执与目标配置同事务提交，供聚合审计恢复核对。';


COMMENT ON COLUMN batch.config_sync_target_receipt.audit_tenant_id IS '发起同步且拥有聚合审计的租户。';


COMMENT ON COLUMN batch.config_sync_target_receipt.log_id IS '本次导入的原始聚合审计记录 ID。';


COMMENT ON COLUMN batch.config_sync_target_receipt.tenant_id IS '本次目标配置事务所属租户，也是回执隔离边界。';


COMMENT ON COLUMN batch.config_sync_target_receipt.result_json IS '已确认的目标执行结果，包含目标租户和原始计数；不得由当前配置反推。';


COMMENT ON COLUMN batch.config_sync_target_receipt.created_at IS '目标执行结果确认并持久化的时间。';


CREATE TABLE batch.console_ai_attachment (
    tenant_id character varying(64) NOT NULL,
    id uuid NOT NULL,
    owner_user_id character varying(64) NOT NULL,
    client_attachment_id uuid NOT NULL,
    input_sha256 character varying(64) NOT NULL,
    object_key character varying(256) NOT NULL,
    media_type character varying(32),
    byte_size bigint,
    width integer,
    height integer,
    status character varying(16) DEFAULT 'UPLOADING'::character varying NOT NULL,
    conversation_id character varying(128),
    turn_no bigint,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_console_ai_attachment_binding CHECK (((((status)::text = 'BOUND'::text) AND (conversation_id IS NOT NULL) AND (turn_no IS NOT NULL)) OR (((status)::text <> 'BOUND'::text) AND (conversation_id IS NULL) AND (turn_no IS NULL)))),
    CONSTRAINT ck_console_ai_attachment_size CHECK (((byte_size > 0) AND (((status)::text = 'UPLOADING'::text) OR ((width > 0) AND (height > 0) AND ((media_type)::text = ANY (ARRAY['image/png'::character varying, 'image/jpeg'::character varying])))))),
    CONSTRAINT ck_console_ai_attachment_status CHECK (((status)::text = ANY (ARRAY['UPLOADING'::character varying, 'DRAFT'::character varying, 'BOUND'::character varying]))),
    CONSTRAINT pk_console_ai_attachment PRIMARY KEY (tenant_id, id)
);

ALTER TABLE ONLY batch.console_ai_attachment FORCE ROW LEVEL SECURITY;


COMMENT ON TABLE batch.console_ai_attachment IS 'AI 会话私有附件元数据；对象正文存放在隔离的对象存储空间';


COMMENT ON COLUMN batch.console_ai_attachment.media_type IS '归一化后允许发送给模型的媒体类型，目前仅支持 image/png 和 image/jpeg';


COMMENT ON COLUMN batch.console_ai_attachment.status IS '附件生命周期状态：UPLOADING 上传中、DRAFT 待绑定、BOUND 已绑定到会话轮次';


CREATE TABLE batch.console_ai_audit_log (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    request_id character varying(128) NOT NULL,
    trace_id character varying(128),
    session_id character varying(128),
    operator_id character varying(64),
    prompt_category character varying(32) NOT NULL,
    prompt_decision character varying(32) NOT NULL,
    model_name character varying(128),
    prompt_hash character varying(128),
    prompt_preview character varying(1024),
    response_hash character varying(128),
    response_preview character varying(1024),
    refusal_reason character varying(512),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    prompt_tokens integer,
    completion_tokens integer,
    estimated_cost_usd numeric(18,8),
    cost_status character varying(16) DEFAULT 'UNPRICED'::character varying NOT NULL,
    CONSTRAINT console_ai_audit_log_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.console_ai_audit_log IS 'Console AI 请求审计；保留哈希、模型和 token 用量，不保存敏感原文。';


COMMENT ON COLUMN batch.console_ai_audit_log.trace_id IS 'AI 调用链路标识。';


COMMENT ON COLUMN batch.console_ai_audit_log.model_name IS '实际调用的 AI 模型名称。';


COMMENT ON COLUMN batch.console_ai_audit_log.prompt_hash IS 'AI 请求正文哈希；用于审计关联，不保存原文。';


COMMENT ON COLUMN batch.console_ai_audit_log.response_hash IS 'AI 响应正文哈希；用于审计关联，不保存原文。';


COMMENT ON COLUMN batch.console_ai_audit_log.prompt_tokens IS '请求 token 用量。';


COMMENT ON COLUMN batch.console_ai_audit_log.completion_tokens IS '响应 token 用量。';


COMMENT ON COLUMN batch.console_ai_audit_log.estimated_cost_usd IS '按 provider 返回 token usage 和运维配置单价计算的费用估算；不代表供应商账单。';


COMMENT ON COLUMN batch.console_ai_audit_log.cost_status IS '费用状态：UNPRICED 未计价，PRICED 已按配置费率估算，RESERVED 缺少 token usage 时按预留上限保守计量。';


CREATE SEQUENCE batch.console_ai_audit_log_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.console_ai_audit_log_id_seq OWNED BY batch.console_ai_audit_log.id;


CREATE TABLE batch.console_ai_conversation (
    id character varying(128) NOT NULL,
    tenant_id character varying(64) NOT NULL,
    owner_user_id character varying(64) NOT NULL,
    title character varying(160) DEFAULT 'AI conversation'::character varying NOT NULL,
    context_version character varying(32) NOT NULL,
    next_turn_no bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_console_ai_conversation_turn_no CHECK ((next_turn_no > 0)),
    CONSTRAINT pk_console_ai_conversation PRIMARY KEY (tenant_id, id)
);

ALTER TABLE ONLY batch.console_ai_conversation FORCE ROW LEVEL SECURITY;


COMMENT ON TABLE batch.console_ai_conversation IS 'Console AI 用户会话。持久化默认关闭；启用时按租户、所有者隔离并按 expires_at 清理。';


COMMENT ON COLUMN batch.console_ai_conversation.context_version IS '创建会话时采用的页面上下文契约版本，用于历史会话兼容。';


CREATE TABLE batch.console_ai_monthly_usage (
    tenant_id character varying(64) NOT NULL,
    billing_month date NOT NULL,
    request_count bigint DEFAULT 0 NOT NULL,
    prompt_tokens bigint DEFAULT 0 NOT NULL,
    completion_tokens bigint DEFAULT 0 NOT NULL,
    estimated_cost_usd numeric(18,8) DEFAULT 0 NOT NULL,
    reserved_cost_usd numeric(18,8) DEFAULT 0 NOT NULL,
    reserved_updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_console_ai_monthly_usage_month CHECK ((billing_month = (date_trunc('month'::text, (billing_month)::timestamp with time zone))::date)),
    CONSTRAINT ck_console_ai_monthly_usage_totals CHECK (((request_count >= 0) AND (prompt_tokens >= 0) AND (completion_tokens >= 0) AND (estimated_cost_usd >= (0)::numeric) AND (reserved_cost_usd >= (0)::numeric))),
    CONSTRAINT pk_console_ai_monthly_usage PRIMARY KEY (tenant_id, billing_month)
);

ALTER TABLE ONLY batch.console_ai_monthly_usage FORCE ROW LEVEL SECURITY;


COMMENT ON TABLE batch.console_ai_monthly_usage IS 'Console AI 租户月度 token 与配置费率估算台账；不代表供应商最终账单。';


CREATE TABLE batch.console_ai_object_cleanup (
    tenant_id character varying(64) NOT NULL,
    object_key character varying(256) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    CONSTRAINT pk_console_ai_object_cleanup PRIMARY KEY (tenant_id, object_key)
);

ALTER TABLE ONLY batch.console_ai_object_cleanup FORCE ROW LEVEL SECURITY;


COMMENT ON TABLE batch.console_ai_object_cleanup IS 'AI 附件对象删除失败后的持久化重试队列';


CREATE TABLE batch.console_ai_turn (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    conversation_id character varying(128) NOT NULL,
    turn_no bigint NOT NULL,
    context_version character varying(32) NOT NULL,
    prompt_text text NOT NULL,
    response_text text,
    turn_status character varying(16) DEFAULT 'IN_PROGRESS'::character varying NOT NULL,
    prompt_decision character varying(32),
    model_name character varying(128),
    prompt_tokens integer,
    completion_tokens integer,
    estimated_cost_usd numeric(18,8),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    completed_at timestamp with time zone,
    client_turn_id uuid,
    CONSTRAINT ck_console_ai_turn_status CHECK (((turn_status)::text = ANY (ARRAY['IN_PROGRESS'::character varying, 'COMPLETE'::character varying, 'FAILED'::character varying, 'REJECTED'::character varying]))),
    CONSTRAINT ck_console_ai_turn_tokens CHECK ((((prompt_tokens IS NULL) OR (prompt_tokens >= 0)) AND ((completion_tokens IS NULL) OR (completion_tokens >= 0)) AND ((estimated_cost_usd IS NULL) OR (estimated_cost_usd >= (0)::numeric)))),
    CONSTRAINT console_ai_turn_pkey PRIMARY KEY (id)
);

ALTER TABLE ONLY batch.console_ai_turn FORCE ROW LEVEL SECURITY;


COMMENT ON TABLE batch.console_ai_turn IS 'Console AI 对话轮次，仅会话持久化显式开启时保存 prompt/response；页面上下文正文不写入本表。';


COMMENT ON COLUMN batch.console_ai_turn.context_version IS '本轮请求采用的页面上下文契约版本。';


COMMENT ON COLUMN batch.console_ai_turn.turn_status IS '对话轮次状态：IN_PROGRESS、COMPLETE、FAILED 或 REJECTED。';


COMMENT ON COLUMN batch.console_ai_turn.model_name IS '处理本轮请求的模型名称；未完成或未调用模型时为空。';


ALTER TABLE batch.console_ai_turn ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME batch.console_ai_turn_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


CREATE TABLE batch.console_external_identity (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    account_id bigint NOT NULL,
    issuer character varying(512) NOT NULL,
    subject character varying(255) NOT NULL,
    linked_by character varying(128) NOT NULL,
    linked_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_console_external_identity_issuer_nonblank CHECK ((length(btrim((issuer)::text)) > 0)),
    CONSTRAINT ck_console_external_identity_subject_nonblank CHECK (((length(btrim((subject)::text)) > 0) AND (length((subject)::text) <= 255))),
    CONSTRAINT console_external_identity_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.console_external_identity IS 'OIDC 外部身份到预置 Console 账号的显式映射；不存放 IdP token 或 client secret。';


COMMENT ON COLUMN batch.console_external_identity.tenant_id IS '租户标识；复合外键保证其与本地账号租户一致。';


COMMENT ON COLUMN batch.console_external_identity.account_id IS '已存在的 batch.console_user_account.id；角色始终取本地账号。';


COMMENT ON COLUMN batch.console_external_identity.issuer IS '经 OIDC issuer 校验的精确 iss 值。';


COMMENT ON COLUMN batch.console_external_identity.subject IS 'OIDC 稳定 sub 标识；不使用 email 或 username 自动认领账号。';


COMMENT ON COLUMN batch.console_external_identity.linked_by IS '执行显式绑定的全局管理员用户名。';


ALTER TABLE batch.console_external_identity ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME batch.console_external_identity_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


CREATE TABLE batch.console_maintenance_state (
    id smallint NOT NULL,
    enabled boolean DEFAULT false NOT NULL,
    read_only boolean DEFAULT false NOT NULL,
    message character varying(2048),
    eta_at timestamp with time zone,
    affected_services jsonb DEFAULT '[]'::jsonb NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    updated_by character varying(64),
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    starts_at timestamp with time zone,
    CONSTRAINT ck_console_maintenance_state_services_array CHECK ((jsonb_typeof(affected_services) = 'array'::text)),
    CONSTRAINT ck_console_maintenance_state_singleton CHECK ((id = 1)),
    CONSTRAINT ck_console_maintenance_state_version CHECK ((version >= 0)),
    CONSTRAINT console_maintenance_state_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.console_maintenance_state IS 'Console 维护状态唯一事实源；所有 Console 副本从该单例读取并按 version 收敛。';


COMMENT ON COLUMN batch.console_maintenance_state.affected_services IS '受影响子系统 code JSON 数组；仅用于维护公告展示。';


COMMENT ON COLUMN batch.console_maintenance_state.version IS '维护状态乐观锁版本；更新必须使用 version CAS。';


COMMENT ON COLUMN batch.console_maintenance_state.starts_at IS '计划维护开始时间；NULL 表示启用维护时立即生效。';


CREATE TABLE batch.console_operation_audit (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    aggregate_type character varying(64) NOT NULL,
    aggregate_id character varying(128) NOT NULL,
    action character varying(128) NOT NULL,
    operator_id character varying(128),
    operator_role character varying(64),
    result character varying(16) DEFAULT 'SUCCESS'::character varying NOT NULL,
    error_code character varying(64),
    error_message character varying(1024),
    params jsonb,
    trace_id character varying(128),
    request_id character varying(128),
    ip_hash character varying(64),
    ua_hash character varying(64),
    event_version integer DEFAULT 1 NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_console_operation_audit_result CHECK (((result)::text = ANY (ARRAY['SUCCESS'::character varying, 'FAILED'::character varying]))),
    CONSTRAINT console_operation_audit_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.console_operation_audit IS '通用控制台用户操作审计(由 @AuditAction Aspect 同事务写入)';


COMMENT ON COLUMN batch.console_operation_audit.aggregate_type IS '审计聚合根类型。';


COMMENT ON COLUMN batch.console_operation_audit.params IS '脱敏后的操作参数 JSON。';


COMMENT ON COLUMN batch.console_operation_audit.trace_id IS '控制台操作链路标识。';


COMMENT ON COLUMN batch.console_operation_audit.ip_hash IS '来源 IP 的不可逆审计哈希。';


COMMENT ON COLUMN batch.console_operation_audit.ua_hash IS '浏览器 User-Agent 的不可逆审计哈希。';


COMMENT ON COLUMN batch.console_operation_audit.event_version IS '审计事件契约版本。';


CREATE SEQUENCE batch.console_operation_audit_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.console_operation_audit_id_seq OWNED BY batch.console_operation_audit.id;


CREATE TABLE batch.console_push_approval_notification (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    approval_no character varying(128) NOT NULL,
    notified_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT console_push_approval_notification_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.console_push_approval_notification IS '审批待办的 Console Push 投递记录。';


CREATE SEQUENCE batch.console_push_approval_notification_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.console_push_approval_notification_id_seq OWNED BY batch.console_push_approval_notification.id;


CREATE TABLE batch.console_push_job_delivery (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    terminal_event_key character varying(256) NOT NULL,
    job_instance_id bigint NOT NULL,
    operator_id character varying(128),
    record_type character varying(16) NOT NULL,
    subscription_id bigint,
    subscription_fingerprint character varying(64),
    decision character varying(32),
    event_type character varying(64) NOT NULL,
    payload_json jsonb NOT NULL,
    delivery_status character varying(16),
    attempt integer DEFAULT 0 NOT NULL,
    next_retry_at timestamp with time zone,
    delivery_claim_token uuid,
    delivery_claim_until timestamp with time zone,
    http_status integer,
    error_message character varying(1024),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_job_push_claim CHECK (((delivery_claim_token IS NULL) = (delivery_claim_until IS NULL))),
    CONSTRAINT ck_job_push_record CHECK (((((record_type)::text = 'DECISION'::text) AND (subscription_id IS NULL) AND (subscription_fingerprint IS NULL) AND (decision IS NOT NULL) AND (delivery_status IS NULL)) OR (((record_type)::text = 'DEVICE'::text) AND (subscription_id IS NOT NULL) AND (subscription_id > 0) AND (subscription_fingerprint IS NOT NULL) AND (length((subscription_fingerprint)::text) = 64) AND (operator_id IS NOT NULL) AND (length((operator_id)::text) > 0) AND (decision IS NULL) AND (delivery_status IS NOT NULL)))),
    CONSTRAINT console_push_job_delivery_attempt_check CHECK ((attempt >= 0)),
    CONSTRAINT console_push_job_delivery_decision_check CHECK (((decision)::text = ANY (ARRAY['DISABLED'::character varying, 'DRY_RUN'::character varying, 'NO_OPERATOR'::character varying, 'NO_SUBSCRIPTIONS'::character varying, 'QUEUED'::character varying]))),
    CONSTRAINT console_push_job_delivery_delivery_status_check CHECK (((delivery_status)::text = ANY (ARRAY['PENDING'::character varying, 'IN_FLIGHT'::character varying, 'ACCEPTED'::character varying, 'GONE'::character varying, 'EXHAUSTED'::character varying]))),
    CONSTRAINT console_push_job_delivery_record_type_check CHECK (((record_type)::text = ANY (ARRAY['DECISION'::character varying, 'DEVICE'::character varying]))),
    CONSTRAINT console_push_job_delivery_pkey PRIMARY KEY (id)
);

ALTER TABLE ONLY batch.console_push_job_delivery FORCE ROW LEVEL SECURITY;


COMMENT ON TABLE batch.console_push_job_delivery IS 'Job 终态 Web Push 首次接纳决定及冻结设备投递；provider 接纳不代表浏览器已读。';


COMMENT ON COLUMN batch.console_push_job_delivery.id IS '决定或设备意图的持久身份。';


COMMENT ON COLUMN batch.console_push_job_delivery.tenant_id IS 'Job 事实与收件设备所属租户。';


COMMENT ON COLUMN batch.console_push_job_delivery.terminal_event_key IS '原 Job 终态事实键，与消费回执同租户关联。';


COMMENT ON COLUMN batch.console_push_job_delivery.job_instance_id IS '原 Job 实例身份，不代替 Workflow 根身份。';


COMMENT ON COLUMN batch.console_push_job_delivery.operator_id IS '事实中冻结的提交人；不从当前配置重新推算。';


COMMENT ON COLUMN batch.console_push_job_delivery.record_type IS '不可变首次决定或逐设备待投递意图。';


COMMENT ON COLUMN batch.console_push_job_delivery.subscription_id IS '首次选中的设备订阅；不设外键以允许用户撤销订阅且保留投递证据。';


COMMENT ON COLUMN batch.console_push_job_delivery.subscription_fingerprint IS '原 tenant/user/device/endpoint/keys 的 SHA-256 摘要，不复制设备凭据。';


COMMENT ON COLUMN batch.console_push_job_delivery.decision IS '首次接纳时的不可变开关/无目标/已排队决定；不是当前送达状态。';


COMMENT ON COLUMN batch.console_push_job_delivery.event_type IS 'Job 原终态的推送展示分类，不是新业务事件总线编码。';


COMMENT ON COLUMN batch.console_push_job_delivery.payload_json IS '首次接纳冻结的通知载荷，不含 endpoint 或设备密钥。';


COMMENT ON COLUMN batch.console_push_job_delivery.delivery_status IS '设备投递状态；ACCEPTED 只表示 provider 已接纳。';


COMMENT ON COLUMN batch.console_push_job_delivery.attempt IS '设备投递尝试次数。';


COMMENT ON COLUMN batch.console_push_job_delivery.next_retry_at IS '下次允许尝试时间，空值表示不自动重投。';


COMMENT ON COLUMN batch.console_push_job_delivery.delivery_claim_token IS '认领身份，旧执行者回写必须被围栏拒绝。';


COMMENT ON COLUMN batch.console_push_job_delivery.delivery_claim_until IS '在途认领期限，过期不代表远端未接纳。';


COMMENT ON COLUMN batch.console_push_job_delivery.http_status IS '已观察到的 provider HTTP 状态；未观察到则为空。';


COMMENT ON COLUMN batch.console_push_job_delivery.error_message IS '有界脱敏的失败/未知结果原因。';


COMMENT ON COLUMN batch.console_push_job_delivery.created_at IS '首次接纳或创建设备意图的时间。';


CREATE SEQUENCE batch.console_push_job_delivery_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.console_push_job_delivery_id_seq OWNED BY batch.console_push_job_delivery.id;


CREATE TABLE batch.console_push_subscription (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    username character varying(128) NOT NULL,
    endpoint text NOT NULL,
    p256dh_key text NOT NULL,
    auth_secret text NOT NULL,
    user_agent character varying(512),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    last_pushed_at timestamp with time zone,
    last_seen_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT console_push_subscription_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.console_push_subscription IS 'Console PWA Web Push 订阅；保存浏览器端点和加密公钥。';


COMMENT ON COLUMN batch.console_push_subscription.endpoint IS '浏览器推送服务 URL — Apple/Google/Mozilla 不同;Apple 是 web.push.apple.com/...';


COMMENT ON COLUMN batch.console_push_subscription.p256dh_key IS 'ECDH P-256 公钥(base64-url),用于加密 payload;前端 PushSubscription.keys.p256dh';


COMMENT ON COLUMN batch.console_push_subscription.auth_secret IS '消息认证密钥(base64-url);前端 PushSubscription.keys.auth';


CREATE SEQUENCE batch.console_push_subscription_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.console_push_subscription_id_seq OWNED BY batch.console_push_subscription.id;


CREATE TABLE batch.console_usage_daily (
    id bigint NOT NULL,
    stat_date date NOT NULL,
    tenant_id character varying(64) NOT NULL,
    source character varying(32) NOT NULL,
    metric_code character varying(96) NOT NULL,
    page_code character varying(128) DEFAULT ''::character varying NOT NULL,
    app_version character varying(64) DEFAULT ''::character varying NOT NULL,
    event_count bigint DEFAULT 0 NOT NULL,
    success_count bigint DEFAULT 0 NOT NULL,
    failure_count bigint DEFAULT 0 NOT NULL,
    last_seen_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_console_usage_daily_counts CHECK (((event_count >= 0) AND (success_count >= 0) AND (failure_count >= 0) AND ((success_count + failure_count) <= event_count))),
    CONSTRAINT ck_console_usage_daily_source CHECK (((source)::text = ANY (ARRAY['OPERATION_AUDIT'::character varying, 'BUSINESS_RESULT'::character varying, 'FRONTEND'::character varying]))),
    CONSTRAINT pk_console_usage_daily PRIMARY KEY (stat_date, tenant_id, source, metric_code, page_code, app_version)
)
PARTITION BY RANGE (stat_date);

ALTER TABLE ONLY batch.console_usage_daily FORCE ROW LEVEL SECURITY;


COMMENT ON TABLE batch.console_usage_daily IS '后端派生的日使用率聚合，仅用于趋势和运营分析，不作为审计或计费事实源。';


COMMENT ON COLUMN batch.console_usage_daily.metric_code IS '后端维护的稳定指标编码，不直接复制任意客户端输入。';


COMMENT ON COLUMN batch.console_usage_daily.app_version IS '前端或客户端版本，用于按版本比较使用情况，可为空语义以空字符串保存。';


ALTER TABLE batch.console_usage_daily ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME batch.console_usage_daily_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


CREATE TABLE batch.console_user_account (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    username character varying(128) NOT NULL,
    display_name character varying(256),
    password_hash character varying(512) NOT NULL,
    authorities_csv character varying(512) NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    must_change_password boolean DEFAULT false NOT NULL,
    CONSTRAINT ck_console_user_account_four_roles CHECK (((authorities_csv)::text ~ '^(ROLE_ADMIN|ROLE_AUDITOR|ROLE_TENANT_ADMIN|ROLE_TENANT_USER)(\s*,\s*(ROLE_ADMIN|ROLE_AUDITOR|ROLE_TENANT_ADMIN|ROLE_TENANT_USER))*$'::text)),
    CONSTRAINT console_user_account_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.console_user_account IS 'Console 用户账号和认证状态。';


COMMENT ON COLUMN batch.console_user_account.password_hash IS '不可逆口令哈希，禁止保存明文。';


COMMENT ON CONSTRAINT ck_console_user_account_four_roles ON batch.console_user_account IS '控制台账号仅允许 ADMIN、AUDITOR、TENANT_ADMIN、TENANT_USER 四类正式角色';


CREATE SEQUENCE batch.console_user_account_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.console_user_account_id_seq OWNED BY batch.console_user_account.id;


CREATE TABLE batch.console_user_batch_operation (
    operation_id uuid NOT NULL,
    request_id uuid NOT NULL,
    actor_username character varying(128) NOT NULL,
    source_digest character varying(64) NOT NULL,
    account_count integer NOT NULL,
    tenant_ids text NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT console_user_batch_operation_account_count_check CHECK (((account_count >= 1) AND (account_count <= 500))),
    CONSTRAINT console_user_batch_operation_pkey PRIMARY KEY (operation_id)
);


COMMENT ON TABLE batch.console_user_batch_operation IS '控制台批量开户操作记录；不保存初始密码明文';


CREATE TABLE batch.custom_task_type_registry (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    task_type_code character varying(128) NOT NULL,
    display_name character varying(256),
    descriptor jsonb NOT NULL,
    descriptor_version character varying(32),
    source character varying(32) DEFAULT 'SDK_DECLARED'::character varying NOT NULL,
    declared_by_worker_code character varying(128),
    status character varying(32) DEFAULT 'ACTIVE'::character varying NOT NULL,
    first_declared_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    last_declared_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_custom_task_type_registry_source CHECK (((source)::text = ANY (ARRAY['SDK_DECLARED'::character varying, 'MANUAL'::character varying]))),
    CONSTRAINT ck_custom_task_type_registry_status CHECK (((status)::text = ANY (ARRAY['ACTIVE'::character varying, 'DEPRECATED'::character varying]))),
    CONSTRAINT custom_task_type_registry_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.custom_task_type_registry IS 'SDK Phase 3 — 租户自定义 taskType 注册表(descriptor 全文 JSONB + 顶层查询列)';


COMMENT ON COLUMN batch.custom_task_type_registry.task_type_code IS 'taskType code,命名 tenant_<tenantId>_<verb>(roadmap §3.1)';


COMMENT ON COLUMN batch.custom_task_type_registry.descriptor IS 'SdkTaskTypeDescriptor 全文:label / defaults / inputSchema / templateVars / version';


COMMENT ON COLUMN batch.custom_task_type_registry.descriptor_version IS 'descriptor 语义版本(变更检测,只在版本变化时刷新存档)';


COMMENT ON COLUMN batch.custom_task_type_registry.source IS 'SDK_DECLARED(worker 上报)/ MANUAL(运营后台手填)';


COMMENT ON COLUMN batch.custom_task_type_registry.declared_by_worker_code IS '最近一次上报该 descriptor 的 worker code';


COMMENT ON COLUMN batch.custom_task_type_registry.status IS 'ACTIVE / DEPRECATED(不兼容升级灰度,旧 code 保留 30 天)';


CREATE SEQUENCE batch.custom_task_type_registry_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.custom_task_type_registry_id_seq OWNED BY batch.custom_task_type_registry.id;


CREATE TABLE batch.data_asset (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    asset_code character varying(128) NOT NULL,
    asset_type character varying(32) DEFAULT 'JOB'::character varying NOT NULL,
    display_name character varying(256),
    owner_job_code character varying(128),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_data_asset_type CHECK (((asset_type)::text = 'JOB'::text)),
    CONSTRAINT data_asset_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.data_asset IS 'BFS 最小资产目录:当前只登记 JOB 产物,用于 asset partition readiness,不是企业数据目录';


COMMENT ON COLUMN batch.data_asset.asset_code IS '资产编码;JOB 类型下等于 job_code';


COMMENT ON COLUMN batch.data_asset.asset_type IS '资产类型;当前只允许 JOB';


COMMENT ON COLUMN batch.data_asset.owner_job_code IS '产出该资产的 BFS job_code';


CREATE SEQUENCE batch.data_asset_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.data_asset_id_seq OWNED BY batch.data_asset.id;


CREATE TABLE batch.data_quality_rule (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    rule_code character varying(128) NOT NULL,
    rule_name character varying(256) NOT NULL,
    rule_type character varying(32) NOT NULL,
    scope_business_key character varying(256) NOT NULL,
    expression text NOT NULL,
    threshold_json jsonb,
    severity character varying(16) DEFAULT 'WARN'::character varying NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    description character varying(512),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_dq_rule_severity CHECK (((severity)::text = ANY (ARRAY['BLOCKER'::character varying, 'WARN'::character varying, 'INFO'::character varying]))),
    CONSTRAINT ck_dq_rule_type CHECK (((rule_type)::text = ANY (ARRAY['ROW_LEVEL'::character varying, 'TABLE_LEVEL'::character varying, 'CROSS_TABLE'::character varying, 'CROSS_DAY'::character varying]))),
    CONSTRAINT data_quality_rule_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.data_quality_rule IS 'ADR-021 数据对账规则定义；scope_business_key 前缀匹配 result_version.business_key';


COMMENT ON COLUMN batch.data_quality_rule.rule_type IS '数据质量规则类型。';


COMMENT ON COLUMN batch.data_quality_rule.threshold_json IS '质量阈值配置 JSON。';


COMMENT ON COLUMN batch.data_quality_rule.severity IS 'BLOCKER 失败阻塞 EFFECTIVE 链路（强制 MANUAL_APPROVAL）；WARN 仅记录；INFO 仅观测';


CREATE SEQUENCE batch.data_quality_rule_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.data_quality_rule_id_seq OWNED BY batch.data_quality_rule.id;


CREATE TABLE batch.disaster_day_override (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    calendar_code character varying(128) NOT NULL,
    biz_date date NOT NULL,
    action character varying(32) NOT NULL,
    reason character varying(1024) NOT NULL,
    approved_by character varying(128) NOT NULL,
    approved_at timestamp with time zone NOT NULL,
    effective_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    ttl_until timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_disaster_action CHECK (((action)::text = ANY (ARRAY['SKIP'::character varying, 'DEFER_TO_NEXT_BIZDAY'::character varying]))),
    CONSTRAINT ck_disaster_ttl_after_effective CHECK ((ttl_until > effective_at)),
    CONSTRAINT disaster_day_override_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.disaster_day_override IS 'ADR-023 灾难日热切换：突发停业不改 holiday 表，独立审计行';


COMMENT ON COLUMN batch.disaster_day_override.action IS 'SKIP: 当日 SKIPPED 不开批量日; DEFER_TO_NEXT_BIZDAY: 推迟到次日';


CREATE SEQUENCE batch.disaster_day_override_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.disaster_day_override_id_seq OWNED BY batch.disaster_day_override.id;


CREATE TABLE batch.expected_work_occurrence (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    calendar_code character varying(128) NOT NULL,
    biz_date date NOT NULL,
    workflow_definition_id bigint NOT NULL,
    source_type character varying(24) NOT NULL,
    source_key character varying(256) NOT NULL,
    generation integer DEFAULT 1 NOT NULL,
    expected_at timestamp with time zone,
    due_at timestamp with time zone,
    occurrence_status character varying(24) DEFAULT 'EXPECTED'::character varying NOT NULL,
    trigger_request_id character varying(128),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    missing_input_alerted_at timestamp with time zone,
    CONSTRAINT ck_expected_work_generation_positive CHECK ((generation > 0)),
    CONSTRAINT ck_expected_work_source_type CHECK (((source_type)::text = ANY (ARRAY['FILE_GROUP'::character varying, 'EXTERNAL_EVENT'::character varying, 'WORKFLOW'::character varying]))),
    CONSTRAINT ck_expected_work_status CHECK (((occurrence_status)::text = ANY (ARRAY['EXPECTED'::character varying, 'ARRIVED'::character varying, 'SATISFIED'::character varying, 'EXPIRED'::character varying, 'CANCELLED'::character varying]))),
    CONSTRAINT expected_work_occurrence_pkey PRIMARY KEY (id)
);

ALTER TABLE ONLY batch.expected_work_occurrence FORCE ROW LEVEL SECURITY;


COMMENT ON TABLE batch.expected_work_occurrence IS '某租户日历业务日预期到达的事件或文件工作；未终结的记录会阻止自动结算空日。';


COMMENT ON COLUMN batch.expected_work_occurrence.workflow_definition_id IS '此预期项所代表业务日义务所属的 Workflow。';


COMMENT ON COLUMN batch.expected_work_occurrence.source_type IS '负责此预期工作义务的生产者类别。';


COMMENT ON COLUMN batch.expected_work_occurrence.source_key IS '预期项生产者提供的稳定身份，例如文件组配置及其对应的发生序号。';


COMMENT ON COLUMN batch.expected_work_occurrence.generation IS '单调递增的义务代次；重新打开时创建新的 EXPECTED 记录，并保留此前的终态记录。';


COMMENT ON COLUMN batch.expected_work_occurrence.occurrence_status IS '预期工作的生命周期，涵盖到达、满足、过期或取消。';


COMMENT ON COLUMN batch.expected_work_occurrence.missing_input_alerted_at IS '逾期 EXPECTED occurrence 首次发出缺少输入告警的时刻；此字段不会解除或使该义务过期。';


CREATE SEQUENCE batch.expected_work_occurrence_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.expected_work_occurrence_id_seq OWNED BY batch.expected_work_occurrence.id;


CREATE TABLE batch.external_event_delivery (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    event_source character varying(128) NOT NULL,
    event_id character varying(256) NOT NULL,
    event_code character varying(128) NOT NULL,
    biz_date date NOT NULL,
    request_id character varying(128) NOT NULL,
    payload_snapshot jsonb NOT NULL,
    target_workflow_definition_ids jsonb NOT NULL,
    trace_id character varying(128) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_external_event_delivery_targets CHECK (
CASE
    WHEN (jsonb_typeof(target_workflow_definition_ids) = 'array'::text) THEN (jsonb_array_length(target_workflow_definition_ids) > 0)
    ELSE false
END),
    CONSTRAINT external_event_delivery_pkey PRIMARY KEY (id)
);

ALTER TABLE ONLY batch.external_event_delivery FORCE ROW LEVEL SECURITY;


COMMENT ON TABLE batch.external_event_delivery IS '已受理的外部事件及其不可变载荷和首次受理时冻结的 Workflow 目标快照。';


COMMENT ON COLUMN batch.external_event_delivery.payload_snapshot IS '外部事件首次受理时冻结的规范化请求载荷。';


COMMENT ON COLUMN batch.external_event_delivery.trace_id IS '首次受理投递的链路标识；幂等重投时保持不变。';


CREATE TABLE batch.external_event_delivery_alias (
    tenant_id character varying(64) NOT NULL,
    idempotency_key character varying(256) NOT NULL,
    delivery_id bigint NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_external_event_delivery_alias PRIMARY KEY (tenant_id, idempotency_key)
);

ALTER TABLE ONLY batch.external_event_delivery_alias FORCE ROW LEVEL SECURITY;


COMMENT ON TABLE batch.external_event_delivery_alias IS '租户范围内用于定位外部事件投递的 HTTP 幂等键。';


COMMENT ON COLUMN batch.external_event_delivery_alias.idempotency_key IS '用于查询事件级投递状态的租户范围 HTTP Idempotency-Key。';


CREATE SEQUENCE batch.external_event_delivery_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.external_event_delivery_id_seq OWNED BY batch.external_event_delivery.id;


CREATE TABLE batch.external_event_expectation_definition (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    calendar_code character varying(128) NOT NULL,
    workflow_definition_id bigint NOT NULL,
    event_code character varying(128) NOT NULL,
    expected_local_time time without time zone NOT NULL,
    due_local_time time without time zone,
    enabled boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_external_event_expectation_due_time CHECK (((due_local_time IS NULL) OR (due_local_time > expected_local_time))),
    CONSTRAINT external_event_expectation_definition_pkey PRIMARY KEY (id)
);

ALTER TABLE ONLY batch.external_event_expectation_definition FORCE ROW LEVEL SECURITY;


COMMENT ON TABLE batch.external_event_expectation_definition IS '租户日历每个已开启业务日对应的周期性外部事件预期项。';


CREATE SEQUENCE batch.external_event_expectation_definition_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.external_event_expectation_definition_id_seq OWNED BY batch.external_event_expectation_definition.id;


CREATE TABLE batch.file_audit_log (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    file_id bigint,
    operation_type character varying(64) NOT NULL,
    operation_result character varying(32) NOT NULL,
    operator_type character varying(32) NOT NULL,
    operator_id character varying(128),
    trace_id character varying(128),
    evidence_ref character varying(512),
    detail_summary jsonb,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_file_audit_operator_type CHECK (((operator_type)::text = ANY (ARRAY['SYSTEM'::character varying, 'USER'::character varying, 'API'::character varying]))),
    CONSTRAINT ck_file_audit_result CHECK (((operation_result)::text = ANY (ARRAY['SUCCESS'::character varying, 'FAILED'::character varying]))),
    CONSTRAINT file_audit_log_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.file_audit_log IS '文件治理操作审计；记录上传、确认、分发和人工操作。';


COMMENT ON COLUMN batch.file_audit_log.operation_type IS '文件治理操作类型。';


COMMENT ON COLUMN batch.file_audit_log.operator_type IS '执行文件治理操作的主体类型。';


COMMENT ON COLUMN batch.file_audit_log.operator_id IS '文件操作主体标识，例如用户或 Worker。';


COMMENT ON COLUMN batch.file_audit_log.trace_id IS '文件治理操作链路标识。';


CREATE SEQUENCE batch.file_audit_log_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.file_audit_log_id_seq OWNED BY batch.file_audit_log.id;


CREATE TABLE batch.file_channel_config (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    channel_code character varying(128) NOT NULL,
    channel_name character varying(256) NOT NULL,
    channel_type character varying(32) NOT NULL,
    target_endpoint character varying(1024),
    auth_type character varying(32) DEFAULT 'NONE'::character varying NOT NULL,
    config_json jsonb NOT NULL,
    receipt_policy character varying(32) DEFAULT 'NONE'::character varying NOT NULL,
    timeout_seconds integer DEFAULT 0 NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    is_deleted boolean DEFAULT false NOT NULL,
    CONSTRAINT ck_file_channel_auth_type CHECK (((auth_type)::text = ANY (ARRAY['NONE'::character varying, 'PASSWORD'::character varying, 'KEY_PAIR'::character varying, 'TOKEN'::character varying, 'OAUTH2'::character varying, 'CUSTOM'::character varying]))),
    CONSTRAINT ck_file_channel_receipt_policy CHECK (((receipt_policy)::text = ANY (ARRAY['NONE'::character varying, 'SYNC'::character varying, 'ASYNC'::character varying, 'POLLING'::character varying]))),
    CONSTRAINT ck_file_channel_timeout CHECK ((timeout_seconds >= 0)),
    CONSTRAINT ck_file_channel_type CHECK (((channel_type)::text = ANY (ARRAY['SFTP'::character varying, 'API'::character varying, 'API_PUSH'::character varying, 'EMAIL'::character varying, 'NAS'::character varying, 'OSS'::character varying, 'LOCAL'::character varying]))),
    CONSTRAINT file_channel_config_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.file_channel_config IS '文件接收或分发通道配置；端点和凭据仅通过受控引用保存。';


COMMENT ON COLUMN batch.file_channel_config.channel_type IS '通道协议或后端类型。';


COMMENT ON COLUMN batch.file_channel_config.target_endpoint IS '目标端点；需经过 SSRF 与协议安全校验。';


COMMENT ON COLUMN batch.file_channel_config.auth_type IS '通道认证方式；凭据从 secret 引用解析。';


COMMENT ON COLUMN batch.file_channel_config.config_json IS '通道协议配置 JSON；敏感字段不允许明文持久化。';


COMMENT ON COLUMN batch.file_channel_config.receipt_policy IS '下游回执确认策略。';


COMMENT ON COLUMN batch.file_channel_config.timeout_seconds IS '单次通道调用超时秒数。';


COMMENT ON COLUMN batch.file_channel_config.is_deleted IS '软删除标记(同 file_template_config); recreate 同 channel_code 走 upsert 自动复活, 不产生幽灵双行';


CREATE SEQUENCE batch.file_channel_config_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.file_channel_config_id_seq OWNED BY batch.file_channel_config.id;


CREATE TABLE batch.file_channel_health (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    channel_code character varying(128) NOT NULL,
    channel_type character varying(32) NOT NULL,
    health_status character varying(32) NOT NULL,
    consecutive_failures integer DEFAULT 0 NOT NULL,
    last_probe_at timestamp with time zone,
    last_success_at timestamp with time zone,
    last_failure_at timestamp with time zone,
    next_probe_at timestamp with time zone,
    probe_message character varying(1024),
    probe_evidence character varying(1024),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_file_channel_health_failures CHECK ((consecutive_failures >= 0)),
    CONSTRAINT ck_file_channel_health_status CHECK (((health_status)::text = ANY (ARRAY['HEALTHY'::character varying, 'DEGRADED'::character varying, 'UNHEALTHY'::character varying]))),
    CONSTRAINT file_channel_health_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.file_channel_health IS '文件通道探活快照；用于投递前健康裁决。';


COMMENT ON COLUMN batch.file_channel_health.channel_type IS '被探测文件通道的协议类型。';


COMMENT ON COLUMN batch.file_channel_health.health_status IS '通道健康状态。';


CREATE SEQUENCE batch.file_channel_health_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.file_channel_health_id_seq OWNED BY batch.file_channel_health.id;


CREATE TABLE batch.file_error_record (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    file_id bigint,
    pipeline_instance_id bigint,
    pipeline_step_run_id bigint,
    record_no bigint,
    error_code character varying(128) NOT NULL,
    error_message character varying(1024),
    error_stage character varying(64),
    is_skipped boolean DEFAULT false NOT NULL,
    skip_action character varying(32),
    raw_record jsonb,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    error_key character varying(128),
    error_args jsonb,
    source_row_num bigint,
    source_column character varying(256),
    CONSTRAINT file_error_record_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.file_error_record IS '文件解析或校验的坏记录明细；支持定位源行和源列。';


COMMENT ON COLUMN batch.file_error_record.error_code IS '坏记录标准化错误码。';


COMMENT ON COLUMN batch.file_error_record.error_message IS '坏记录错误摘要。';


COMMENT ON COLUMN batch.file_error_record.error_stage IS '坏记录产生的处理阶段。';


COMMENT ON COLUMN batch.file_error_record.error_key IS '同 pipeline_step_run.error_key';


COMMENT ON COLUMN batch.file_error_record.error_args IS '同 pipeline_step_run.error_args';


COMMENT ON COLUMN batch.file_error_record.source_row_num IS '源文件 1-based 物理行号(Excel 行 / 文本行);用于坏行回原表定位,非 Excel 路径可空';


COMMENT ON COLUMN batch.file_error_record.source_column IS '出错列的表头名;无法定位到具体列时为空';


CREATE SEQUENCE batch.file_error_record_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.file_error_record_id_seq OWNED BY batch.file_error_record.id;


CREATE TABLE batch.file_governance_reconcile_cursor (
    storage_id character varying(64) NOT NULL,
    object_prefix text NOT NULL,
    next_marker text,
    revisit_marker text,
    revisit_budget bigint DEFAULT 1 NOT NULL,
    revisit_remaining bigint DEFAULT 1 NOT NULL,
    next_lane character varying(8) DEFAULT 'FORWARD'::character varying NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT file_governance_reconcile_cursor_check CHECK (((revisit_remaining > 0) AND (revisit_remaining <= revisit_budget))),
    CONSTRAINT file_governance_reconcile_cursor_next_lane_check CHECK (((next_lane)::text = ANY (ARRAY['FORWARD'::character varying, 'REVISIT'::character varying]))),
    CONSTRAINT file_governance_reconcile_cursor_revisit_budget_check CHECK ((revisit_budget > 0)),
    CONSTRAINT file_governance_reconcile_cursor_storage_id_check CHECK (((storage_id)::text ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT file_governance_reconcile_cursor_version_check CHECK ((version >= 0)),
    CONSTRAINT file_governance_reconcile_cursor_pkey PRIMARY KEY (storage_id, object_prefix)
);


COMMENT ON TABLE batch.file_governance_reconcile_cursor IS '对象治理对账共享的有界分页进度，不表达文件处理状态。';


COMMENT ON COLUMN batch.file_governance_reconcile_cursor.storage_id IS '物理存储、桶和部署命名空间的摘要。';


COMMENT ON COLUMN batch.file_governance_reconcile_cursor.object_prefix IS '本次对账使用的对象前缀，空字符串表示整个桶。';


COMMENT ON COLUMN batch.file_governance_reconcile_cursor.next_marker IS '下次对账页的起点；空值表示从该前缀的起点扫描。';


COMMENT ON COLUMN batch.file_governance_reconcile_cursor.revisit_marker IS '回访游标，用于发现前向游标之前后来出现的对象。';


COMMENT ON COLUMN batch.file_governance_reconcile_cursor.revisit_budget IS '本轮回访最多读取的页数。';


COMMENT ON COLUMN batch.file_governance_reconcile_cursor.revisit_remaining IS '本轮回访尚可读取的页数。';


COMMENT ON COLUMN batch.file_governance_reconcile_cursor.next_lane IS '下一次执行前向扫描或回访扫描。';


COMMENT ON COLUMN batch.file_governance_reconcile_cursor.version IS '分页进度乐观并发版本。';


COMMENT ON COLUMN batch.file_governance_reconcile_cursor.updated_at IS '分页进度最近更新时间。';


CREATE TABLE batch.file_group_expectation_definition (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    calendar_code character varying(128) NOT NULL,
    workflow_definition_id bigint NOT NULL,
    file_group_code character varying(128) NOT NULL,
    expected_local_time time without time zone NOT NULL,
    due_local_time time without time zone,
    enabled boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_file_group_expectation_due_time CHECK (((due_local_time IS NULL) OR (due_local_time > expected_local_time))),
    CONSTRAINT file_group_expectation_definition_pkey PRIMARY KEY (id)
);

ALTER TABLE ONLY batch.file_group_expectation_definition FORCE ROW LEVEL SECURITY;


COMMENT ON TABLE batch.file_group_expectation_definition IS '配置的租户日历每个已开启业务日对应的周期性文件组预期项。';


CREATE SEQUENCE batch.file_group_expectation_definition_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.file_group_expectation_definition_id_seq OWNED BY batch.file_group_expectation_definition.id;


CREATE TABLE batch.file_template_config (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    template_code character varying(128) NOT NULL,
    template_name character varying(256) NOT NULL,
    template_type character varying(32) NOT NULL,
    biz_type character varying(64),
    file_format_type character varying(32) NOT NULL,
    charset character varying(32),
    target_charset character varying(32),
    with_bom boolean DEFAULT false NOT NULL,
    line_separator character varying(16),
    delimiter character varying(8),
    quote_char character varying(8),
    escape_char character varying(8),
    record_length integer DEFAULT 0 NOT NULL,
    header_rows integer DEFAULT 0 NOT NULL,
    footer_rows integer DEFAULT 0 NOT NULL,
    header_template jsonb,
    trailer_template jsonb,
    checksum_type character varying(32) DEFAULT 'NONE'::character varying NOT NULL,
    compress_type character varying(32) DEFAULT 'NONE'::character varying NOT NULL,
    encrypt_type character varying(32) DEFAULT 'NONE'::character varying NOT NULL,
    naming_rule character varying(512),
    field_mappings jsonb,
    validation_rule_set jsonb,
    default_query_code character varying(128),
    default_query_sql text,
    query_param_schema jsonb,
    streaming_enabled boolean DEFAULT true NOT NULL,
    page_size integer DEFAULT 1000 NOT NULL,
    fetch_size integer DEFAULT 1000 NOT NULL,
    chunk_size integer DEFAULT 500 NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    version integer DEFAULT 1 NOT NULL,
    description character varying(1024),
    created_by character varying(64),
    updated_by character varying(64),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    preprocess_pipeline jsonb,
    preview_masking_enabled boolean DEFAULT false NOT NULL,
    error_line_masking_enabled boolean DEFAULT false NOT NULL,
    log_masking_enabled boolean DEFAULT false NOT NULL,
    content_encryption_enabled boolean DEFAULT false NOT NULL,
    encryption_key_ref character varying(256),
    download_requires_approval boolean DEFAULT false NOT NULL,
    masking_rule_set character varying(128),
    export_data_ref character varying(128),
    load_target_ref character varying(128),
    is_deleted boolean DEFAULT false NOT NULL,
    CONSTRAINT ck_file_template_checksum_type CHECK (((checksum_type)::text = ANY (ARRAY['NONE'::character varying, 'MD5'::character varying, 'SHA-256'::character varying]))),
    CONSTRAINT ck_file_template_chunk_size CHECK ((chunk_size > 0)),
    CONSTRAINT ck_file_template_compress_type CHECK (((compress_type)::text = ANY (ARRAY['NONE'::character varying, 'ZIP'::character varying, 'GZIP'::character varying]))),
    CONSTRAINT ck_file_template_encrypt_type CHECK (((encrypt_type)::text = ANY (ARRAY['NONE'::character varying, 'AES'::character varying, 'PGP'::character varying, 'CUSTOM'::character varying]))),
    CONSTRAINT ck_file_template_fetch_size CHECK ((fetch_size > 0)),
    CONSTRAINT ck_file_template_footer_rows CHECK ((footer_rows >= 0)),
    CONSTRAINT ck_file_template_format CHECK (((file_format_type)::text = ANY (ARRAY['DELIMITED'::character varying, 'FIXED_WIDTH'::character varying, 'EXCEL'::character varying, 'XML'::character varying, 'JSON'::character varying, 'BINARY'::character varying]))),
    CONSTRAINT ck_file_template_header_rows CHECK ((header_rows >= 0)),
    CONSTRAINT ck_file_template_page_size CHECK ((page_size > 0)),
    CONSTRAINT ck_file_template_record_length CHECK ((record_length >= 0)),
    CONSTRAINT ck_file_template_type CHECK (((template_type)::text = ANY (ARRAY['IMPORT'::character varying, 'EXPORT'::character varying, 'SHARED'::character varying]))),
    CONSTRAINT ck_file_template_version CHECK ((version > 0)),
    CONSTRAINT file_template_config_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.file_template_config IS '文件格式、校验、脱敏、加密和处理目标的租户模板配置。';


COMMENT ON COLUMN batch.file_template_config.template_type IS '模板用途类型；区分导入、导出和分发场景。';


COMMENT ON COLUMN batch.file_template_config.biz_type IS '模板适用业务类型。';


COMMENT ON COLUMN batch.file_template_config.file_format_type IS '文件格式类型；决定解析和写出实现。';


COMMENT ON COLUMN batch.file_template_config.checksum_type IS '模板要求的完整性校验算法。';


COMMENT ON COLUMN batch.file_template_config.compress_type IS '压缩算法配置。';


COMMENT ON COLUMN batch.file_template_config.encrypt_type IS '应用层或存储层加密类型。';


COMMENT ON COLUMN batch.file_template_config.naming_rule IS '生成文件名的受控规则。';


COMMENT ON COLUMN batch.file_template_config.validation_rule_set IS '文件校验规则集引用。';


COMMENT ON COLUMN batch.file_template_config.version IS '模板配置乐观锁版本。';


COMMENT ON COLUMN batch.file_template_config.preprocess_pipeline IS '导入前预处理流水线引用。';


COMMENT ON COLUMN batch.file_template_config.preview_masking_enabled IS 'Console 预览和样例行的脱敏开关。';


COMMENT ON COLUMN batch.file_template_config.error_line_masking_enabled IS '坏记录和错误行持久化时的脱敏开关。';


COMMENT ON COLUMN batch.file_template_config.log_masking_enabled IS '校验和质量检查日志消息的脱敏开关。';


COMMENT ON COLUMN batch.file_template_config.content_encryption_enabled IS '对象是否使用应用层加密存储；加密类型和密钥引用见 encrypt_type 与 encryption_key_ref。';


COMMENT ON COLUMN batch.file_template_config.encryption_key_ref IS '加密密钥引用；禁止保存密钥明文。';


COMMENT ON COLUMN batch.file_template_config.download_requires_approval IS '预签名下载所需的审批记录 ID。';


COMMENT ON COLUMN batch.file_template_config.masking_rule_set IS '可选的命名脱敏规则集，例如 PCI_BASIC。';


COMMENT ON COLUMN batch.file_template_config.export_data_ref IS 'GenerateStep 使用的 ExportDataPlugin 标识；EXPORT 模板必须设置。支持 sql_template_export（需要 default_query_sql），以及 jdbc_mapped_export（需要 query_param_schema.jdbcMappedExport）。';


COMMENT ON COLUMN batch.file_template_config.load_target_ref IS 'LoadStep 使用的 ImportLoadPlugin 标识；IMPORT 模板必须设置。支持 jdbc_mapped（需要 query_param_schema.jdbcMappedImport）。';


COMMENT ON COLUMN batch.file_template_config.is_deleted IS '软删除标记(true = 已删除);业务 SELECT 默认过滤,delete 路径 UPDATE,upsert 路径 ON CONFLICT 复活 is_deleted=false。CommonFragments.xml#activePredicate';


CREATE SEQUENCE batch.file_template_config_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.file_template_config_id_seq OWNED BY batch.file_template_config.id;


CREATE TABLE batch.forensic_export_log (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    export_id character varying(128) NOT NULL,
    biz_date_from date NOT NULL,
    biz_date_to date NOT NULL,
    job_codes jsonb,
    scope jsonb NOT NULL,
    export_format character varying(32) NOT NULL,
    status character varying(32) NOT NULL,
    storage_path character varying(1024),
    file_size_bytes bigint,
    sha256 character varying(64),
    row_counts jsonb,
    error_message character varying(2048),
    requested_by character varying(128) NOT NULL,
    requested_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    completed_at timestamp with time zone,
    retention_until date,
    trace_id character varying(128),
    CONSTRAINT ck_forensic_export_dates CHECK ((biz_date_from <= biz_date_to)),
    CONSTRAINT ck_forensic_export_format CHECK (((export_format)::text = ANY (ARRAY['BUNDLE'::character varying, 'JSON'::character varying, 'CSV'::character varying]))),
    CONSTRAINT ck_forensic_export_status CHECK (((status)::text = ANY (ARRAY['PROCESSING'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying]))),
    CONSTRAINT forensic_export_log_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.forensic_export_log IS 'ADR-022 v0.1 forensic 取证导出元数据 — 一行 = 一次 export 请求';


COMMENT ON COLUMN batch.forensic_export_log.scope IS 'JSONB 数组: ["job_instances","files","retries","approvals","operations","audits"] 等';


COMMENT ON COLUMN batch.forensic_export_log.status IS '取证导出任务状态。';


COMMENT ON COLUMN batch.forensic_export_log.sha256 IS 'export bundle 的 SHA-256 hex; 监管复盘验证完整性';


COMMENT ON COLUMN batch.forensic_export_log.row_counts IS 'JSONB: {"job_instances": 12, "files": 5, "retries": 2, ...} 实际导出行数, 便于核对';


COMMENT ON COLUMN batch.forensic_export_log.retention_until IS 'v0.1 留 NULL; v0.2 接 OSS 后由 retention 策略填 (默认 7 年)';


COMMENT ON COLUMN batch.forensic_export_log.trace_id IS '取证导出链路标识。';


CREATE SEQUENCE batch.forensic_export_log_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.forensic_export_log_id_seq OWNED BY batch.forensic_export_log.id;


CREATE TABLE batch.idempotency_record (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    idempotency_key character varying(512) NOT NULL,
    result_payload text,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT idempotency_record_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.idempotency_record IS '全局幂等保护记录；每行代表一次已执行操作，按租户和业务键确定身份。';


COMMENT ON COLUMN batch.idempotency_record.idempotency_key IS '调用方定义的唯一幂等键，例如 trigger_request:tenantId:requestId 或 node_dispatch:workflowRunId:nodeCode。';


COMMENT ON COLUMN batch.idempotency_record.result_payload IS '可选的 JSON 结果；供调用方查询此前执行的结果。';


CREATE SEQUENCE batch.idempotency_record_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.idempotency_record_id_seq OWNED BY batch.idempotency_record.id;


CREATE TABLE batch.import_ingress_ownership (
    scope_id character varying(64) NOT NULL,
    storage_id character varying(64) NOT NULL,
    bucket_name text NOT NULL,
    object_prefix text NOT NULL,
    state character varying(16) DEFAULT 'RETIRED'::character varying NOT NULL,
    updated_by text DEFAULT CURRENT_USER NOT NULL,
    change_reason text,
    created_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    updated_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT import_ingress_ownership_bucket_name_check CHECK ((length(bucket_name) > 0)),
    CONSTRAINT import_ingress_ownership_scope_id_check CHECK (((scope_id)::text ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT import_ingress_ownership_state_check CHECK (((state)::text = ANY (ARRAY['ACTIVE'::character varying, 'RETIRED'::character varying, 'RELEASED'::character varying]))),
    CONSTRAINT import_ingress_ownership_storage_id_check CHECK (((storage_id)::text ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT import_ingress_ownership_pkey PRIMARY KEY (scope_id)
);

ALTER TABLE ONLY batch.import_ingress_ownership FORCE ROW LEVEL SECURITY;


COMMENT ON TABLE batch.import_ingress_ownership IS '入站扫描范围的跨组件权威声明，退休后仍保护，明确释放后才交还普通对象对账。';


COMMENT ON COLUMN batch.import_ingress_ownership.scope_id IS '存储身份与完整扫描前缀的摘要，与扫描协调范围一致。';


COMMENT ON COLUMN batch.import_ingress_ownership.storage_id IS '物理存储、桶和部署命名空间的摘要。';


COMMENT ON COLUMN batch.import_ingress_ownership.bucket_name IS '声明所属对象存储桶。';


COMMENT ON COLUMN batch.import_ingress_ownership.object_prefix IS '完整 scanner 数据前缀；允许空前缀表示整个桶。';


COMMENT ON COLUMN batch.import_ingress_ownership.state IS 'ACTIVE 允许扫描并保护；RETIRED 停止扫描但仍保护；RELEASED 显式归还普通对账。';


COMMENT ON COLUMN batch.import_ingress_ownership.updated_by IS '最近一次声明或状态变更的数据库账号。';


COMMENT ON COLUMN batch.import_ingress_ownership.change_reason IS '最近一次状态变更原因；释放范围时必须提供。';


COMMENT ON COLUMN batch.import_ingress_ownership.created_at IS '首次声明时间。';


COMMENT ON COLUMN batch.import_ingress_ownership.updated_at IS '声明状态最近变化时间。';


CREATE TABLE batch.import_ingress_ownership_binding (
    id bigint NOT NULL,
    scope_id character varying(64) NOT NULL,
    binding_type character varying(16) NOT NULL,
    tenant_id character varying(128) NOT NULL,
    object_prefix text NOT NULL,
    file_group_code character varying(128),
    manifest_key_template text,
    channel_code character varying(128),
    enabled boolean DEFAULT true NOT NULL,
    CONSTRAINT ck_ingress_ownership_binding_fields CHECK (((((binding_type)::text = 'MANIFEST'::text) AND (file_group_code IS NOT NULL) AND (manifest_key_template IS NOT NULL) AND (channel_code IS NULL)) OR (((binding_type)::text = 'CHANNEL'::text) AND (channel_code IS NOT NULL) AND (file_group_code IS NULL) AND (manifest_key_template IS NULL)))),
    CONSTRAINT import_ingress_ownership_binding_binding_type_check CHECK (((binding_type)::text = ANY (ARRAY['MANIFEST'::character varying, 'CHANNEL'::character varying]))),
    CONSTRAINT import_ingress_ownership_binding_object_prefix_check CHECK (((length(object_prefix) > 0) AND ("right"(object_prefix, 1) = '/'::text))),
    CONSTRAINT import_ingress_ownership_binding_tenant_id_check CHECK ((length((tenant_id)::text) > 0)),
    CONSTRAINT import_ingress_ownership_binding_pkey PRIMARY KEY (id)
);

ALTER TABLE ONLY batch.import_ingress_ownership_binding FORCE ROW LEVEL SECURITY;


COMMENT ON TABLE batch.import_ingress_ownership_binding IS '入口范围下按租户和目录声明的清单或渠道绑定。';


COMMENT ON COLUMN batch.import_ingress_ownership_binding.id IS '绑定版本主键；停用后保留历史声明。';


COMMENT ON COLUMN batch.import_ingress_ownership_binding.scope_id IS '所属扫描范围。';


COMMENT ON COLUMN batch.import_ingress_ownership_binding.binding_type IS 'MANIFEST 为权威清单绑定，CHANNEL 为可信渠道绑定。';


COMMENT ON COLUMN batch.import_ingress_ownership_binding.tenant_id IS '绑定所属租户。';


COMMENT ON COLUMN batch.import_ingress_ownership_binding.object_prefix IS '租户数据目录，匹配时采用最长前缀。';


COMMENT ON COLUMN batch.import_ingress_ownership_binding.file_group_code IS '清单绑定的文件组编码。';


COMMENT ON COLUMN batch.import_ingress_ownership_binding.manifest_key_template IS '包含单个业务日占位符的权威清单完整对象键模板。';


COMMENT ON COLUMN batch.import_ingress_ownership_binding.channel_code IS '可信文件到达渠道编码。';


COMMENT ON COLUMN batch.import_ingress_ownership_binding.enabled IS '是否供扫描器使用；停用的历史清单仍受归属保护。';


ALTER TABLE batch.import_ingress_ownership_binding ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME batch.import_ingress_ownership_binding_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


CREATE TABLE batch.import_ingress_scan_object (
    id bigint NOT NULL,
    scope_id character varying(64) NOT NULL,
    object_digest character varying(64) NOT NULL,
    object_key text NOT NULL,
    size_bytes bigint NOT NULL,
    etag text,
    last_modified timestamp with time zone,
    first_observed_at timestamp with time zone NOT NULL,
    seen_cycle bigint NOT NULL,
    seen_window bigint DEFAULT 0 NOT NULL,
    is_manifest boolean NOT NULL,
    manifest_wait_reason character varying(32),
    CONSTRAINT ck_import_ingress_scan_object_values CHECK (((size_bytes >= 0) AND (seen_cycle > 0))),
    CONSTRAINT import_ingress_scan_object_object_digest_check CHECK (((object_digest)::text ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT import_ingress_scan_object_seen_window_check CHECK ((seen_window >= 0)),
    CONSTRAINT import_ingress_scan_object_pkey PRIMARY KEY (id)
);

ALTER TABLE ONLY batch.import_ingress_scan_object FORCE ROW LEVEL SECURITY;


COMMENT ON TABLE batch.import_ingress_scan_object IS '导入入口扫描到的对象快照和稳定批次清单索引。';


COMMENT ON COLUMN batch.import_ingress_scan_object.id IS '扫描记录标识，供阶段内有序分页。';


COMMENT ON COLUMN batch.import_ingress_scan_object.scope_id IS '所属扫描存储范围摘要。';


COMMENT ON COLUMN batch.import_ingress_scan_object.object_digest IS '对象键摘要；写入时仍需核对原始对象键防止摘要冲突。';


COMMENT ON COLUMN batch.import_ingress_scan_object.object_key IS '对象存储键，可能包含租户与业务路径。';


COMMENT ON COLUMN batch.import_ingress_scan_object.size_bytes IS '最近一次观察到的对象字节数。';


COMMENT ON COLUMN batch.import_ingress_scan_object.etag IS '最近一次观察到的对象版本标识。';


COMMENT ON COLUMN batch.import_ingress_scan_object.last_modified IS '最近一次观察到的对象修改时间。';


COMMENT ON COLUMN batch.import_ingress_scan_object.first_observed_at IS '当前对象快照首次观察时间，供稳定窗口判断。';


COMMENT ON COLUMN batch.import_ingress_scan_object.seen_cycle IS '最近一次枚举时所属的完整扫描周期。';


COMMENT ON COLUMN batch.import_ingress_scan_object.seen_window IS '最近一次枚举时所属的有界发现窗口。';


COMMENT ON COLUMN batch.import_ingress_scan_object.is_manifest IS '对象键是否属于批次清单协议。';


COMMENT ON COLUMN batch.import_ingress_scan_object.manifest_wait_reason IS '成员等待权威清单的最近原因，用于原因变化时记录告警。';


ALTER TABLE batch.import_ingress_scan_object ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME batch.import_ingress_scan_object_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


CREATE TABLE batch.import_ingress_scan_state (
    scope_id character varying(64) NOT NULL,
    storage_id character varying(64) NOT NULL,
    bucket_name text NOT NULL,
    object_prefix text NOT NULL,
    config_digest character varying(64),
    phase character varying(16) DEFAULT 'DISCOVER'::character varying NOT NULL,
    cycle bigint DEFAULT 1 NOT NULL,
    list_marker text,
    after_object_id bigint DEFAULT 0 NOT NULL,
    window_id bigint DEFAULT 0 NOT NULL,
    revisit_marker text,
    revisit_budget bigint DEFAULT 1 NOT NULL,
    revisit_remaining bigint DEFAULT 1 NOT NULL,
    next_lane character varying(8) DEFAULT 'FORWARD'::character varying NOT NULL,
    cleanup_required boolean DEFAULT false NOT NULL,
    apply_after_id bigint DEFAULT 0 NOT NULL,
    apply_upper_id bigint DEFAULT 0 NOT NULL,
    fence bigint DEFAULT 0 NOT NULL,
    lease_owner uuid,
    lease_until timestamp with time zone,
    waiting_since timestamp with time zone,
    waiting_until timestamp with time zone,
    updated_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT ck_import_ingress_apply_cursor CHECK (((apply_upper_id >= 0) AND (apply_after_id <= apply_upper_id))),
    CONSTRAINT ck_import_ingress_revisit_budget CHECK (((revisit_remaining > 0) AND (revisit_remaining <= revisit_budget))),
    CONSTRAINT ck_import_ingress_scan_config CHECK (((config_digest IS NOT NULL) OR ((phase)::text = ANY (ARRAY['DISCOVER'::character varying, 'MAINTENANCE'::character varying])))),
    CONSTRAINT ck_import_ingress_scan_counters CHECK (((cycle > 0) AND (after_object_id >= 0) AND (fence >= 0))),
    CONSTRAINT ck_import_ingress_scan_lease CHECK (((lease_owner IS NULL) = (lease_until IS NULL))),
    CONSTRAINT ck_import_ingress_scan_phase CHECK (((phase)::text = ANY (ARRAY['DISCOVER'::character varying, 'INDEX'::character varying, 'APPLY'::character varying, 'CLEAN'::character varying, 'MAINTENANCE'::character varying]))),
    CONSTRAINT ck_import_ingress_scan_wait CHECK (((waiting_since IS NULL) = (waiting_until IS NULL))),
    CONSTRAINT import_ingress_scan_state_apply_after_id_check CHECK ((apply_after_id >= 0)),
    CONSTRAINT import_ingress_scan_state_config_digest_check CHECK (((config_digest)::text ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT import_ingress_scan_state_next_lane_check CHECK (((next_lane)::text = ANY (ARRAY['FORWARD'::character varying, 'REVISIT'::character varying]))),
    CONSTRAINT import_ingress_scan_state_revisit_budget_check CHECK ((revisit_budget > 0)),
    CONSTRAINT import_ingress_scan_state_scope_id_check CHECK (((scope_id)::text ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT import_ingress_scan_state_window_id_check CHECK ((window_id >= 0)),
    CONSTRAINT import_ingress_scan_state_pkey PRIMARY KEY (scope_id)
);

ALTER TABLE ONLY batch.import_ingress_scan_state FORCE ROW LEVEL SECURITY;


COMMENT ON TABLE batch.import_ingress_scan_state IS '导入入口按存储范围维护的分页周期、租约及围栏状态。';


COMMENT ON COLUMN batch.import_ingress_scan_state.scope_id IS '物理存储范围与扫描前缀组成的摘要，不包含凭据。';


COMMENT ON COLUMN batch.import_ingress_scan_state.storage_id IS '所属物理存储桶的扫描租约标识。';


COMMENT ON COLUMN batch.import_ingress_scan_state.bucket_name IS '扫描目标对象存储桶。';


COMMENT ON COLUMN batch.import_ingress_scan_state.object_prefix IS '扫描目标前缀，可能包含业务路径。';


COMMENT ON COLUMN batch.import_ingress_scan_state.config_digest IS '扫描语义配置的规范化摘要；维护切换后首次扫描重新绑定。';


COMMENT ON COLUMN batch.import_ingress_scan_state.phase IS '当前扫描周期阶段。';


COMMENT ON COLUMN batch.import_ingress_scan_state.cycle IS '当前完整扫描周期编号。';


COMMENT ON COLUMN batch.import_ingress_scan_state.list_marker IS '当前枚举阶段的对象存储续页标记。';


COMMENT ON COLUMN batch.import_ingress_scan_state.after_object_id IS '当前处理阶段已完成的对象记录游标。';


COMMENT ON COLUMN batch.import_ingress_scan_state.window_id IS '当前有界发现窗口编号。';


COMMENT ON COLUMN batch.import_ingress_scan_state.revisit_marker IS '从前缀起点回扫时的对象存储续页标记。';


COMMENT ON COLUMN batch.import_ingress_scan_state.revisit_budget IS '当前回扫轮次允许的最大页数。';


COMMENT ON COLUMN batch.import_ingress_scan_state.revisit_remaining IS '当前回扫轮次剩余的页数。';


COMMENT ON COLUMN batch.import_ingress_scan_state.next_lane IS '下一次发现使用前向扫描还是回扫。';


COMMENT ON COLUMN batch.import_ingress_scan_state.cleanup_required IS '是否有已完整枚举周期需要清理旧快照。';


COMMENT ON COLUMN batch.import_ingress_scan_state.apply_after_id IS '当前成员处理轮次已完成的记录游标。';


COMMENT ON COLUMN batch.import_ingress_scan_state.apply_upper_id IS '当前成员处理轮次开始时确定的记录上界。';


COMMENT ON COLUMN batch.import_ingress_scan_state.fence IS '扫描租约的单调递增围栏。';


COMMENT ON COLUMN batch.import_ingress_scan_state.lease_owner IS '当前扫描执行者标识。';


COMMENT ON COLUMN batch.import_ingress_scan_state.lease_until IS '当前扫描租约的数据库时间截止点。';


COMMENT ON COLUMN batch.import_ingress_scan_state.waiting_since IS '扫描范围进入共享存储等待队列的时间。';


COMMENT ON COLUMN batch.import_ingress_scan_state.waiting_until IS '等待请求失活的数据库时间截止点。';


COMMENT ON COLUMN batch.import_ingress_scan_state.updated_at IS '扫描状态最近更新时间。';


CREATE TABLE batch.import_ingress_scan_storage_lease (
    storage_id character varying(64) NOT NULL,
    lease_owner uuid,
    lease_until timestamp with time zone,
    fence bigint DEFAULT 0 NOT NULL,
    updated_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT ck_import_ingress_storage_fence CHECK ((fence >= 0)),
    CONSTRAINT ck_import_ingress_storage_lease CHECK (((lease_owner IS NULL) = (lease_until IS NULL))),
    CONSTRAINT import_ingress_scan_storage_lease_storage_id_check CHECK (((storage_id)::text ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT import_ingress_scan_storage_lease_pkey PRIMARY KEY (storage_id)
);

ALTER TABLE ONLY batch.import_ingress_scan_storage_lease FORCE ROW LEVEL SECURITY;


COMMENT ON TABLE batch.import_ingress_scan_storage_lease IS '同一物理对象存储桶内的扫描租约，协调重叠前缀。';


COMMENT ON COLUMN batch.import_ingress_scan_storage_lease.storage_id IS '物理存储位置、桶和部署命名空间的摘要，不包含凭据或前缀。';


COMMENT ON COLUMN batch.import_ingress_scan_storage_lease.lease_owner IS '当前存储范围扫描执行者标识。';


COMMENT ON COLUMN batch.import_ingress_scan_storage_lease.lease_until IS '存储范围扫描租约的数据库时间截止点。';


COMMENT ON COLUMN batch.import_ingress_scan_storage_lease.fence IS '存储范围扫描租约的单调递增围栏。';


COMMENT ON COLUMN batch.import_ingress_scan_storage_lease.updated_at IS '存储范围扫描租约最近更新时间。';


CREATE TABLE batch.job_definition (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    job_code character varying(128) NOT NULL,
    job_name character varying(256) NOT NULL,
    job_type character varying(32) NOT NULL,
    biz_type character varying(64),
    schedule_type character varying(32) NOT NULL,
    schedule_expr character varying(256),
    timezone character varying(64) NOT NULL,
    priority integer DEFAULT 5 NOT NULL,
    queue_code character varying(128),
    worker_group character varying(128),
    calendar_code character varying(128),
    window_code character varying(128),
    trigger_mode character varying(32) DEFAULT 'SCHEDULED'::character varying NOT NULL,
    dag_enabled boolean DEFAULT false NOT NULL,
    shard_strategy character varying(32) DEFAULT 'NONE'::character varying NOT NULL,
    retry_policy character varying(32) DEFAULT 'NONE'::character varying NOT NULL,
    retry_max_count integer DEFAULT 0 NOT NULL,
    timeout_seconds integer DEFAULT 0 NOT NULL,
    execution_handler character varying(256),
    param_schema jsonb,
    default_params jsonb,
    version integer DEFAULT 1 NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    description character varying(1024),
    created_by character varying(64),
    updated_by character varying(64),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    execution_mode character varying(16) DEFAULT 'FULL'::character varying NOT NULL,
    watermark_field character varying(64),
    previous_day_dependency_scope character varying(32) DEFAULT 'INHERIT'::character varying NOT NULL,
    job_group_code character varying(64),
    retry_policy_by_class jsonb,
    depends_on_job_code character varying(64),
    CONSTRAINT chk_job_definition_execution_mode CHECK (((execution_mode)::text = ANY (ARRAY['FULL'::character varying, 'INCREMENTAL'::character varying, 'CDC'::character varying]))),
    CONSTRAINT ck_job_definition_job_type CHECK (((job_type)::text = ANY (ARRAY['GENERAL'::character varying, 'IMPORT'::character varying, 'EXPORT'::character varying, 'PROCESS'::character varying, 'DISPATCH'::character varying, 'ATOMIC'::character varying, 'BUNDLE_IMPORT'::character varying, 'BUNDLE_EXPORT'::character varying, 'BUNDLE_DISPATCH'::character varying]))),
    CONSTRAINT ck_job_definition_previous_day_dependency_scope CHECK (((previous_day_dependency_scope)::text = ANY (ARRAY['INHERIT'::character varying, 'NONE'::character varying, 'SAME_JOB'::character varying, 'SAME_JOB_GROUP'::character varying, 'SAME_CALENDAR'::character varying, 'CUSTOM_CHAIN'::character varying]))),
    CONSTRAINT ck_job_definition_priority CHECK (((priority >= 1) AND (priority <= 9))),
    CONSTRAINT ck_job_definition_retry_max_count CHECK ((retry_max_count >= 0)),
    CONSTRAINT ck_job_definition_retry_policy CHECK (((retry_policy)::text = ANY (ARRAY['NONE'::character varying, 'FIXED'::character varying, 'EXPONENTIAL'::character varying]))),
    CONSTRAINT ck_job_definition_schedule_expr_required CHECK ((((schedule_type)::text <> ALL (ARRAY['CRON'::character varying, 'FIXED_RATE'::character varying])) OR ((schedule_expr IS NOT NULL) AND ((schedule_expr)::text <> ''::text)))),
    CONSTRAINT ck_job_definition_schedule_type CHECK (((schedule_type)::text = ANY (ARRAY['CRON'::character varying, 'FIXED_RATE'::character varying, 'MANUAL'::character varying, 'EVENT'::character varying, 'ONE_TIME'::character varying]))),
    CONSTRAINT ck_job_definition_shard_strategy CHECK (((shard_strategy)::text = ANY (ARRAY['NONE'::character varying, 'STATIC'::character varying, 'DYNAMIC'::character varying, 'AUTO'::character varying]))),
    CONSTRAINT ck_job_definition_timeout_seconds CHECK ((timeout_seconds >= 0)),
    CONSTRAINT ck_job_definition_trigger_mode CHECK (((trigger_mode)::text = ANY (ARRAY['SCHEDULED'::character varying, 'API'::character varying, 'MANUAL'::character varying, 'EVENT'::character varying, 'MIXED'::character varying]))),
    CONSTRAINT job_definition_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.job_definition IS '作业定义权威配置；Console 写入，Orchestrator 在 launch 时读取并生成 job_instance。';


COMMENT ON COLUMN batch.job_definition.job_type IS '任务执行类型。';


COMMENT ON COLUMN batch.job_definition.biz_type IS '任务定义所属业务类型。';


COMMENT ON COLUMN batch.job_definition.schedule_type IS '调度类型；决定 schedule_expr 的解释方式和触发器实现。';


COMMENT ON COLUMN batch.job_definition.schedule_expr IS '调度表达式；按 schedule_type 解析，cron 使用显式时区。';


COMMENT ON COLUMN batch.job_definition.timezone IS '作业调度时区；禁止使用 JVM 默认时区。';


COMMENT ON COLUMN batch.job_definition.priority IS '调度优先级；数值越大越优先。';


COMMENT ON COLUMN batch.job_definition.worker_group IS '默认目标 worker 组；调度时用于能力和容量匹配。';


COMMENT ON COLUMN batch.job_definition.calendar_code IS '关联业务日历编码；决定 biz_date 和批量日闸门。';


COMMENT ON COLUMN batch.job_definition.window_code IS '关联运行窗口编码；窗口外由 out_of_window_action 决定行为。';


COMMENT ON COLUMN batch.job_definition.trigger_mode IS '触发模式；区分自动调度、手工触发和外部触发入口。';


COMMENT ON COLUMN batch.job_definition.shard_strategy IS '分片策略；决定 job_instance 如何拆分为 job_partition。';


COMMENT ON COLUMN batch.job_definition.retry_policy IS '实例级失败重试策略。';


COMMENT ON COLUMN batch.job_definition.retry_max_count IS '实例级最大重试次数。';


COMMENT ON COLUMN batch.job_definition.timeout_seconds IS '实例级执行超时秒数。';


COMMENT ON COLUMN batch.job_definition.default_params IS '作业默认参数 JSON；在创建实例时快照到 params_snapshot。';


COMMENT ON COLUMN batch.job_definition.version IS '乐观锁版本；配置变更时递增。';


COMMENT ON COLUMN batch.job_definition.execution_mode IS '执行模式：FULL、INCREMENTAL 或 CDC。';


COMMENT ON COLUMN batch.job_definition.watermark_field IS 'INCREMENTAL 模式使用的水位列名，例如 update_time 或 id。';


COMMENT ON COLUMN batch.job_definition.previous_day_dependency_scope IS 'Job 级前一批量日依赖范围；INHERIT 表示采用 business_calendar.day_rollover_policy。';


COMMENT ON COLUMN batch.job_definition.job_group_code IS '业务分组码 — SAME_JOB_GROUP 前日门闩用; NULL 时该 scope 退化为 SAME_JOB 语义';


COMMENT ON COLUMN batch.job_definition.retry_policy_by_class IS 'ADR-012 按 class 重试策略 JSONB; 例: {"INFRASTRUCTURE":{"strategy":"EXPONENTIAL","maxAttempts":5}}; NULL = 用全局 retry_policy';


COMMENT ON COLUMN batch.job_definition.depends_on_job_code IS 'ADR-043: 上游 job code;非空时本触发器 fire 前要求该 job 同 bizDate 已 SUCCESS,否则跳过本次';


COMMENT ON CONSTRAINT ck_job_definition_job_type ON batch.job_definition IS '仅允许可执行的 Job 类型；Workflow 定义作为独立的顶层执行对象。';


CREATE SEQUENCE batch.job_definition_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.job_definition_id_seq OWNED BY batch.job_definition.id;


CREATE TABLE batch.job_dependency (
    tenant_id character varying(64) NOT NULL,
    downstream_job_code character varying(128) NOT NULL,
    upstream_job_code character varying(128) NOT NULL,
    dependency_order smallint NOT NULL,
    readiness_mode character varying(32) DEFAULT 'EFFECTIVE_RESULT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    biz_date_alignment character varying(32) DEFAULT 'SAME_DAY'::character varying NOT NULL,
    CONSTRAINT ck_job_dependency_biz_date_alignment CHECK (((biz_date_alignment)::text = ANY (ARRAY['SAME_DAY'::character varying, 'PREVIOUS_BIZ_DAY'::character varying]))),
    CONSTRAINT ck_job_dependency_not_self CHECK (((downstream_job_code)::text <> (upstream_job_code)::text)),
    CONSTRAINT ck_job_dependency_order CHECK ((dependency_order >= 0)),
    CONSTRAINT ck_job_dependency_readiness_mode CHECK (((readiness_mode)::text = 'EFFECTIVE_RESULT'::text)),
    CONSTRAINT pk_job_dependency PRIMARY KEY (tenant_id, downstream_job_code, upstream_job_code)
);

ALTER TABLE ONLY batch.job_dependency FORCE ROW LEVEL SECURITY;


COMMENT ON TABLE batch.job_dependency IS '作业间调度依赖；一个下游可声明多个上游，调度 fire 需全部满足 readiness_mode';


COMMENT ON COLUMN batch.job_dependency.dependency_order IS 'Console 展示和验证时的稳定顺序；依赖判断采用 AND 语义';


COMMENT ON COLUMN batch.job_dependency.readiness_mode IS '本版本固定 EFFECTIVE_RESULT，与旧 depends_on_job_code 的 readiness 语义一致';


COMMENT ON COLUMN batch.job_dependency.biz_date_alignment IS '上游结果业务日对齐策略；PREVIOUS_BIZ_DAY 使用现有周一至周五算术，不感知节假日';


CREATE TABLE batch.job_dependency_waiting_launch (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    request_id character varying(128) NOT NULL,
    job_code character varying(128) NOT NULL,
    biz_date date NOT NULL,
    dependency_snapshot jsonb NOT NULL,
    launch_payload jsonb NOT NULL,
    wait_status character varying(24) DEFAULT 'WAITING'::character varying NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    last_not_ready_reason character varying(512),
    last_checked_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    released_at timestamp with time zone,
    timeout_alerted_at timestamp with time zone,
    CONSTRAINT ck_job_dependency_wait_attempt CHECK ((attempt_count >= 0)),
    CONSTRAINT ck_job_dependency_wait_status CHECK (((wait_status)::text = ANY (ARRAY['WAITING'::character varying, 'RELEASED'::character varying, 'CANCELLED'::character varying]))),
    CONSTRAINT job_dependency_waiting_launch_pkey PRIMARY KEY (id)
);

ALTER TABLE ONLY batch.job_dependency_waiting_launch FORCE ROW LEVEL SECURITY;


COMMENT ON TABLE batch.job_dependency_waiting_launch IS '持久化尚未满足作业上游就绪条件的 Launch 请求；保留原业务日、依赖规则快照和完整请求参数供恢复器重放';


COMMENT ON COLUMN batch.job_dependency_waiting_launch.dependency_snapshot IS '首次进入等待时的上游作业编码快照，恢复时不因配置变更漂移';


COMMENT ON COLUMN batch.job_dependency_waiting_launch.launch_payload IS '完整 LaunchRequest JSON 快照，重启及事件重投后用于幂等恢复';


COMMENT ON COLUMN batch.job_dependency_waiting_launch.wait_status IS '依赖请求生命周期：WAITING 等待上游，RELEASED 已释放，CANCELLED 已取消';


COMMENT ON COLUMN batch.job_dependency_waiting_launch.timeout_alerted_at IS '一次性超时告警的 CAS 认领时刻；不改变 WAITING 状态。';


CREATE SEQUENCE batch.job_dependency_waiting_launch_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.job_dependency_waiting_launch_id_seq OWNED BY batch.job_dependency_waiting_launch.id;


CREATE TABLE batch.job_instance_dedup_key (
    tenant_id character varying(64) NOT NULL,
    dedup_key character varying(256) NOT NULL,
    run_attempt integer NOT NULL,
    first_instance_id bigint,
    first_biz_date date,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT job_instance_dedup_key_pkey PRIMARY KEY (tenant_id, dedup_key, run_attempt)
);


COMMENT ON TABLE batch.job_instance_dedup_key IS '跨分区作业实例幂等账本；确保同 tenant、dedup_key 和 run_attempt 唯一。';


COMMENT ON COLUMN batch.job_instance_dedup_key.dedup_key IS '实例创建幂等键。';


CREATE TABLE batch.job_monitoring_alert_claim (
    tenant_id character varying(64) NOT NULL,
    job_instance_id bigint NOT NULL,
    violation_type character varying(32) NOT NULL,
    claimed_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_job_monitoring_alert_claim_type CHECK (((violation_type)::text = ANY (ARRAY['RUNNING_TOO_LONG'::character varying, 'NOT_STARTED'::character varying, 'COMPLETED_LATE'::character varying, 'NOT_COMPLETED_BY_DEADLINE'::character varying, 'FAILED_PARTITION'::character varying]))),
    CONSTRAINT pk_job_monitoring_alert_claim PRIMARY KEY (tenant_id, job_instance_id, violation_type)
);


COMMENT ON TABLE batch.job_monitoring_alert_claim IS '异步作业监控告警的幂等 claim；与 job_instance 生命周期写入隔离。';


COMMENT ON COLUMN batch.job_monitoring_alert_claim.violation_type IS '告警违规类型；与租户和作业实例共同构成幂等 claim 主键。';


CREATE TABLE batch.job_monitoring_policy (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    job_definition_id bigint NOT NULL,
    soft_runtime_seconds integer DEFAULT 0 NOT NULL,
    start_grace_seconds integer DEFAULT 0 NOT NULL,
    completion_deadline_seconds integer DEFAULT 0 NOT NULL,
    updated_by character varying(64),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    completion_deadline_updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    soft_runtime_severity character varying(16) DEFAULT 'WARN'::character varying NOT NULL,
    start_grace_severity character varying(16) DEFAULT 'WARN'::character varying NOT NULL,
    completion_deadline_severity character varying(16) DEFAULT 'WARN'::character varying NOT NULL,
    completion_deadline_local_time time without time zone,
    completion_deadline_day_offset smallint DEFAULT 0 NOT NULL,
    dependency_completion_window_seconds integer DEFAULT 0 NOT NULL,
    soft_runtime_updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    start_grace_updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_job_monitoring_policy_completion_day_offset CHECK (((completion_deadline_day_offset >= 0) AND (completion_deadline_day_offset <= 1))),
    CONSTRAINT ck_job_monitoring_policy_completion_deadline CHECK ((completion_deadline_seconds >= 0)),
    CONSTRAINT ck_job_monitoring_policy_completion_deadline_severity CHECK (((completion_deadline_severity)::text = ANY (ARRAY['WARN'::character varying, 'ERROR'::character varying, 'CRITICAL'::character varying]))),
    CONSTRAINT ck_job_monitoring_policy_dependency_completion_window CHECK ((dependency_completion_window_seconds >= 0)),
    CONSTRAINT ck_job_monitoring_policy_soft_runtime CHECK ((soft_runtime_seconds >= 0)),
    CONSTRAINT ck_job_monitoring_policy_soft_runtime_severity CHECK (((soft_runtime_severity)::text = ANY (ARRAY['WARN'::character varying, 'ERROR'::character varying, 'CRITICAL'::character varying]))),
    CONSTRAINT ck_job_monitoring_policy_start_grace CHECK ((start_grace_seconds >= 0)),
    CONSTRAINT ck_job_monitoring_policy_start_grace_severity CHECK (((start_grace_severity)::text = ANY (ARRAY['WARN'::character varying, 'ERROR'::character varying, 'CRITICAL'::character varying]))),
    CONSTRAINT job_monitoring_policy_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.job_monitoring_policy IS '按租户配置的作业监控阈值。零表示关闭对应监控；硬执行超时仍由 job_definition.timeout_seconds 控制。';


COMMENT ON COLUMN batch.job_monitoring_policy.soft_runtime_seconds IS '运行耗时告警阈值；触发告警不会中断作业执行。';


COMMENT ON COLUMN batch.job_monitoring_policy.start_grace_seconds IS '从计划触发时刻到 Worker 启动的允许延迟；仅实例具有 scheduledAt 时适用。';


COMMENT ON COLUMN batch.job_monitoring_policy.completion_deadline_seconds IS '为兼容旧配置而保留的已弃用字段；定时作业未按时完成告警已改用 completion_deadline_local_time 和 completion_deadline_day_offset。现有秒数值不会自动转换。';


COMMENT ON COLUMN batch.job_monitoring_policy.completion_deadline_updated_at IS '完成期限策略最近一次变更时间；普通作业编辑和其他监控阈值变更不会重置完成过晚的基准。';


COMMENT ON COLUMN batch.job_monitoring_policy.soft_runtime_severity IS '运行时长软阈值违规告警级别；默认 WARN 以保持现有行为。';


COMMENT ON COLUMN batch.job_monitoring_policy.start_grace_severity IS '定时作业错过启动宽限期时使用的告警级别。';


COMMENT ON COLUMN batch.job_monitoring_policy.completion_deadline_severity IS '作业晚于配置截止时间完成时使用的告警级别。';


COMMENT ON COLUMN batch.job_monitoring_policy.completion_deadline_local_time IS '定时作业的本地完成截止时刻，按 job_definition.timezone 解释；NULL 表示关闭此定时作业告警。';


COMMENT ON COLUMN batch.job_monitoring_policy.completion_deadline_day_offset IS '本地完成截止日期相对计划触发日的日历日偏移：0 表示当天，1 表示次日。';


COMMENT ON COLUMN batch.job_monitoring_policy.dependency_completion_window_seconds IS '依赖作业从上游 EFFECTIVE 就绪时刻起允许的最长完成秒数；零表示关闭此规则。';


COMMENT ON COLUMN batch.job_monitoring_policy.soft_runtime_updated_at IS '运行耗时阈值最近生效时刻；仅阈值改变时刷新，严重级别和其他规则编辑不重置。';


COMMENT ON COLUMN batch.job_monitoring_policy.start_grace_updated_at IS '启动宽限阈值最近生效时刻；不补报此前已完成的启动行为。';


CREATE SEQUENCE batch.job_monitoring_policy_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.job_monitoring_policy_id_seq OWNED BY batch.job_monitoring_policy.id;


CREATE TABLE batch.notification_channel (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    channel_code character varying(64) NOT NULL,
    channel_name character varying(128) NOT NULL,
    channel_type character varying(32) NOT NULL,
    config_json jsonb DEFAULT '{}'::jsonb NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    created_by character varying(64),
    updated_by character varying(64),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    is_deleted boolean DEFAULT false NOT NULL,
    CONSTRAINT ck_notification_channel_type CHECK (((channel_type)::text = ANY (ARRAY['EMAIL'::character varying, 'DINGTALK'::character varying, 'WECOM'::character varying, 'WEBHOOK'::character varying, 'SMS'::character varying]))),
    CONSTRAINT notification_channel_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.notification_channel IS '通知通道配置；敏感凭据只能使用引用或加密载荷。';


COMMENT ON COLUMN batch.notification_channel.channel_type IS '通知协议或渠道类型。';


COMMENT ON COLUMN batch.notification_channel.config_json IS '通道配置 JSON；敏感值必须加密或引用化。';


COMMENT ON COLUMN batch.notification_channel.is_deleted IS '软删除标记(同上); recreate 同 channel_code 走 upsert 自动复活';


CREATE SEQUENCE batch.notification_channel_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.notification_channel_id_seq OWNED BY batch.notification_channel.id;


CREATE TABLE batch.notification_delivery_log (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    rule_id bigint NOT NULL,
    channel_code character varying(64) NOT NULL,
    event_type character varying(64) NOT NULL,
    alert_event_id bigint,
    payload_json jsonb NOT NULL,
    delivery_status character varying(32) DEFAULT 'PENDING'::character varying NOT NULL,
    error_message character varying(1024),
    attempt integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    error_key character varying(128),
    error_args jsonb,
    terminal_event_key character varying(256),
    next_retry_at timestamp with time zone,
    delivery_claim_token uuid,
    delivery_claim_until timestamp with time zone,
    CONSTRAINT ck_notification_delivery_status CHECK (((delivery_status)::text = ANY (ARRAY['PENDING'::character varying, 'SUCCESS'::character varying, 'FAILED'::character varying, 'EXHAUSTED'::character varying]))),
    CONSTRAINT notification_delivery_log_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.notification_delivery_log IS '通知投递尝试明细。';


COMMENT ON COLUMN batch.notification_delivery_log.event_type IS '通知对应的业务事件类型。';


COMMENT ON COLUMN batch.notification_delivery_log.payload_json IS '通知渲染载荷 JSON。';


COMMENT ON COLUMN batch.notification_delivery_log.delivery_status IS '通知投递状态。';


COMMENT ON COLUMN batch.notification_delivery_log.error_key IS '同 pipeline_step_run.error_key';


COMMENT ON COLUMN batch.notification_delivery_log.error_args IS '同 pipeline_step_run.error_args';


COMMENT ON COLUMN batch.notification_delivery_log.terminal_event_key IS '可靠 Workflow 终态投递的稳定事件身份。';


COMMENT ON COLUMN batch.notification_delivery_log.next_retry_at IS '可靠终态通知下次投递时间；空值表示无需自动投递。';


COMMENT ON COLUMN batch.notification_delivery_log.delivery_claim_token IS '本轮投递认领 token，回写必须匹配。';


COMMENT ON COLUMN batch.notification_delivery_log.delivery_claim_until IS '认领租约截止时间，过期可恢复。';


CREATE SEQUENCE batch.notification_delivery_log_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.notification_delivery_log_id_seq OWNED BY batch.notification_delivery_log.id;


CREATE TABLE batch.outbox_event_dedup_key (
    tenant_id character varying(64) NOT NULL,
    event_key character varying(256) NOT NULL,
    first_event_id bigint,
    first_created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT outbox_event_dedup_key_pkey PRIMARY KEY (tenant_id, event_key)
);


COMMENT ON TABLE batch.outbox_event_dedup_key IS '跨分区 Outbox 事件键幂等账本；确保同 tenant 和 event_key 只发布一次。';


CREATE TABLE batch.quota_runtime_state (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    quota_scope character varying(64) NOT NULL,
    owner_code character varying(128) NOT NULL,
    quota_reset_policy character varying(32) DEFAULT 'NONE'::character varying NOT NULL,
    window_started_at timestamp with time zone,
    window_expires_at timestamp with time zone,
    peak_borrowed_count integer DEFAULT 0 NOT NULL,
    last_reset_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_quota_runtime_state_peak CHECK ((peak_borrowed_count >= 0)),
    CONSTRAINT ck_quota_runtime_state_policy CHECK (((quota_reset_policy)::text = ANY (ARRAY['NONE'::character varying, 'CALENDAR_DAY'::character varying, 'SLIDING_WINDOW'::character varying]))),
    CONSTRAINT quota_runtime_state_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.quota_runtime_state IS '配额运行态；以 CAS 维护窗口内已占用和借用容量。';


COMMENT ON COLUMN batch.quota_runtime_state.quota_scope IS '配额作用域；例如 tenant、queue 或 fair-share group。';


COMMENT ON COLUMN batch.quota_runtime_state.owner_code IS '配额作用域拥有者编码。';


COMMENT ON COLUMN batch.quota_runtime_state.quota_reset_policy IS '配额计数重置策略。';


COMMENT ON COLUMN batch.quota_runtime_state.window_started_at IS '当前配额窗口开始时点。';


COMMENT ON COLUMN batch.quota_runtime_state.window_expires_at IS '当前配额窗口结束时点。';


COMMENT ON COLUMN batch.quota_runtime_state.version IS '配额 CAS 乐观锁版本。';


CREATE SEQUENCE batch.quota_runtime_state_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.quota_runtime_state_id_seq OWNED BY batch.quota_runtime_state.id;


CREATE TABLE batch.readiness_wait (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    workflow_definition_id bigint NOT NULL,
    workflow_code character varying(128) NOT NULL,
    calendar_code character varying(128),
    schedule_version integer NOT NULL,
    depends_on_job_codes text NOT NULL,
    biz_date date NOT NULL,
    scheduled_fire_time timestamp with time zone NOT NULL,
    deferred_since timestamp with time zone NOT NULL,
    deadline timestamp with time zone NOT NULL,
    wait_status character varying(24) DEFAULT 'PENDING'::character varying NOT NULL,
    request_id character varying(128),
    trace_id character varying(128),
    last_error_code character varying(128),
    released_at timestamp with time zone,
    timed_out_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_readiness_wait_status CHECK (((wait_status)::text = ANY (ARRAY['PENDING'::character varying, 'RELEASED'::character varying, 'TIMED_OUT'::character varying, 'CANCELLED'::character varying]))),
    CONSTRAINT readiness_wait_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.readiness_wait IS 'Orchestrator 接管的 readiness 持久等待；超窗终结后不再阻塞批日结算。';


COMMENT ON COLUMN batch.readiness_wait.tenant_id IS '租户；等待与恢复均在该租户上下文内推进。';


COMMENT ON COLUMN batch.readiness_wait.workflow_definition_id IS '被延迟的 workflow 定义 ID（原 fire 身份之一，与 scheduled_fire_time 共同唯一）。';


COMMENT ON COLUMN batch.readiness_wait.workflow_code IS '被延迟的 workflow code，用于审计与恢复调用。';


COMMENT ON COLUMN batch.readiness_wait.calendar_code IS '业务日历 code，用于与批日实例/结算口径对齐。';


COMMENT ON COLUMN batch.readiness_wait.schedule_version IS '发起等待时的调度版本；恢复时用于识别定义是否已变更。';


COMMENT ON COLUMN batch.readiness_wait.depends_on_job_codes IS '等待的上游 job code 集合（原依赖，逗号分隔）；全部依赖同批次日 EFFECTIVE 才释放。';


COMMENT ON COLUMN batch.readiness_wait.biz_date IS '对齐后的业务日；就绪判定与结算均按该业务日。';


COMMENT ON COLUMN batch.readiness_wait.scheduled_fire_time IS '原始计划 fire 时间；恢复启动必须保持该身份，不重新计算。';


COMMENT ON COLUMN batch.readiness_wait.deferred_since IS '等待起算时间（首次 defer 的实际回调时间）；与 deadline 共同表达等待窗口。';


COMMENT ON COLUMN batch.readiness_wait.deadline IS '等待截止时间；扫尾器在无 Quartz 回调时据此终结等待。';


COMMENT ON COLUMN batch.readiness_wait.wait_status IS 'PENDING=等待中 / RELEASED=已就绪并驱动恢复 / TIMED_OUT=超窗终结 / CANCELLED=已被正常 fire 取消。';


COMMENT ON COLUMN batch.readiness_wait.request_id IS '释放后由 Trigger 重建的 launch requestId；PENDING 时为空。';


COMMENT ON COLUMN batch.readiness_wait.trace_id IS '触发链路的 traceId，便于按链路排查移交与恢复过程。';


COMMENT ON COLUMN batch.readiness_wait.last_error_code IS '最近一次推进失败或终结的原因码（如 readiness-window-exceeded / fired-by-trigger）。';


COMMENT ON COLUMN batch.readiness_wait.released_at IS '就绪并成功驱动恢复启动的时间。';


COMMENT ON COLUMN batch.readiness_wait.timed_out_at IS '超窗终结的时间；从此批日结算不再被该等待阻塞。';


CREATE SEQUENCE batch.readiness_wait_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.readiness_wait_id_seq OWNED BY batch.readiness_wait.id;


CREATE TABLE batch.replay_operation (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    operation_key uuid NOT NULL,
    operation_type character varying(32) NOT NULL,
    status character varying(32) NOT NULL,
    calendar_code character varying(128),
    from_date date,
    through_date date,
    scope character varying(32),
    execution_mode character varying(32),
    candidate_source character varying(32),
    scope_payload jsonb,
    result_policy character varying(64),
    config_version_policy character varying(64),
    config_version integer,
    source_workflow_run_id bigint,
    source_workflow_node_run_id bigint,
    source_job_instance_id bigint,
    target_request_id character varying(128),
    target_workflow_run_id bigint,
    request_hash character varying(64) NOT NULL,
    snapshot_hash character varying(64) NOT NULL,
    snapshot_payload jsonb NOT NULL,
    reason character varying(1024) NOT NULL,
    requested_by character varying(128) NOT NULL,
    approved_by character varying(128),
    total_count integer DEFAULT 0 NOT NULL,
    succeeded_count integer DEFAULT 0 NOT NULL,
    failed_count integer DEFAULT 0 NOT NULL,
    in_flight_count integer DEFAULT 0 NOT NULL,
    started_at timestamp with time zone,
    completed_at timestamp with time zone,
    trace_id character varying(128),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_replay_operation_day_range CHECK (((((operation_type)::text = 'BATCH_DAY_RANGE'::text) AND (calendar_code IS NOT NULL) AND (from_date IS NOT NULL) AND (through_date IS NOT NULL) AND (through_date >= from_date)) OR (((operation_type)::text <> 'BATCH_DAY_RANGE'::text) AND (from_date IS NULL) AND (through_date IS NULL)))),
    CONSTRAINT ck_replay_operation_nonnegative_counts CHECK (((total_count >= 0) AND (succeeded_count >= 0) AND (failed_count >= 0) AND (in_flight_count >= 0))),
    CONSTRAINT ck_replay_operation_status CHECK (((status)::text = ANY (ARRAY['PENDING_APPROVAL'::character varying, 'REJECTED'::character varying, 'RUNNING'::character varying, 'SUCCEEDED'::character varying, 'PARTIAL_FAILED'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying]))),
    CONSTRAINT ck_replay_operation_type CHECK (((operation_type)::text = ANY (ARRAY['BATCH_DAY_RANGE'::character varying, 'WORKFLOW_NODE'::character varying, 'WORKFLOW_RUN'::character varying]))),
    CONSTRAINT ck_replay_operation_workflow_target CHECK (((((operation_type)::text = 'BATCH_DAY_RANGE'::text) AND (source_workflow_run_id IS NULL) AND (source_workflow_node_run_id IS NULL)) OR (((operation_type)::text = 'WORKFLOW_NODE'::text) AND (source_workflow_run_id IS NOT NULL) AND (source_workflow_node_run_id IS NOT NULL) AND (source_job_instance_id IS NOT NULL)) OR (((operation_type)::text = 'WORKFLOW_RUN'::text) AND (source_workflow_run_id IS NOT NULL) AND (source_workflow_node_run_id IS NULL) AND (source_job_instance_id IS NULL)))),
    CONSTRAINT replay_operation_pkey PRIMARY KEY (id)
);

ALTER TABLE ONLY batch.replay_operation FORCE ROW LEVEL SECURITY;


COMMENT ON TABLE batch.replay_operation IS 'ADR-049 重放操作及其审计根记录；日期会话独立执行并保留该操作身份。';


COMMENT ON COLUMN batch.replay_operation.operation_key IS '由预览生成并对客户端可见的稳定操作身份；同租户以相同键重复提交时返回同一操作。';


COMMENT ON COLUMN batch.replay_operation.operation_type IS '重放范围：日期区间、指定 Workflow 节点，或整个来源 WorkflowRun。';


COMMENT ON COLUMN batch.replay_operation.status IS '此重放操作持久化的审批、执行及终态。';


COMMENT ON COLUMN batch.replay_operation.execution_mode IS '重放操作选择的执行模式，包括正式重放或 dry-run。';


COMMENT ON COLUMN batch.replay_operation.scope_payload IS '此重放操作冻结的范围专属请求身份和参数。';


COMMENT ON COLUMN batch.replay_operation.result_policy IS '控制重放新结果如何版本化并生效的策略。';


COMMENT ON COLUMN batch.replay_operation.config_version_policy IS '选择重放所用冻结 Workflow 和 Job 配置版本的策略。';


COMMENT ON COLUMN batch.replay_operation.config_version IS '所选策略要求明确指定版本时记录的目标配置版本。';


COMMENT ON COLUMN batch.replay_operation.request_hash IS '规范化业务请求字段的指纹；用于检测操作键被不同输入重复使用。';


COMMENT ON COLUMN batch.replay_operation.snapshot_hash IS '将已批准操作绑定到经过审核的预览快照的哈希值。';


COMMENT ON COLUMN batch.replay_operation.snapshot_payload IS '审批和恢复所需的冻结来源、配置、结果版本、目标及副作用预览。';


COMMENT ON COLUMN batch.replay_operation.trace_id IS '可观测性关联标识；不参与操作身份或请求去重。';


COMMENT ON CONSTRAINT ck_replay_operation_status ON batch.replay_operation IS 'Replay Operation 允许的生命周期状态；明确区分审批拒绝与取消。';


CREATE SEQUENCE batch.replay_operation_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.replay_operation_id_seq OWNED BY batch.replay_operation.id;


CREATE TABLE batch.replay_operation_preview_token (
    tenant_id character varying(64) NOT NULL,
    token_hash character varying(64) NOT NULL,
    operation_key uuid NOT NULL,
    request_hash character varying(64) NOT NULL,
    snapshot_hash character varying(64) NOT NULL,
    snapshot_payload jsonb NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    consumed_at timestamp with time zone,
    operation_id bigint,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_replay_operation_preview_token PRIMARY KEY (tenant_id, token_hash)
);

ALTER TABLE ONLY batch.replay_operation_preview_token FORCE ROW LEVEL SECURITY;


COMMENT ON TABLE batch.replay_operation_preview_token IS '短时有效、租户隔离的一次性预览凭证；仅保存令牌摘要和冻结的预览快照。';


COMMENT ON COLUMN batch.replay_operation_preview_token.token_hash IS '一次性预览密钥的 SHA-256 摘要；原始令牌仅返回给调用方。';


COMMENT ON COLUMN batch.replay_operation_preview_token.request_hash IS '与此预览凭证绑定的规范化请求字段指纹。';


COMMENT ON COLUMN batch.replay_operation_preview_token.snapshot_hash IS '用于在凭证消费前验证已审核预览快照未发生变化的哈希值。';


COMMENT ON COLUMN batch.replay_operation_preview_token.snapshot_payload IS '与此凭证绑定、并在一次性提交前校验的预览快照。';


CREATE TABLE batch.resource_queue (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    queue_code character varying(128) NOT NULL,
    queue_name character varying(256) NOT NULL,
    queue_type character varying(32) NOT NULL,
    max_running_jobs integer DEFAULT 0 NOT NULL,
    max_running_partitions integer DEFAULT 0 NOT NULL,
    max_qps integer DEFAULT 0 NOT NULL,
    worker_group character varying(128),
    resource_tag character varying(64),
    priority_policy character varying(32) DEFAULT 'FIFO'::character varying NOT NULL,
    fair_share_weight integer DEFAULT 1 NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    description character varying(512),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    fair_share_group character varying(128),
    burst_limit integer DEFAULT 0 NOT NULL,
    quota_reset_policy character varying(32) DEFAULT 'NONE'::character varying NOT NULL,
    group_shared_max_running_jobs integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_resource_queue_fair_share_weight CHECK ((fair_share_weight > 0)),
    CONSTRAINT ck_resource_queue_max_qps CHECK ((max_qps >= 0)),
    CONSTRAINT ck_resource_queue_max_running_jobs CHECK ((max_running_jobs >= 0)),
    CONSTRAINT ck_resource_queue_max_running_partitions CHECK ((max_running_partitions >= 0)),
    CONSTRAINT ck_resource_queue_priority_policy CHECK (((priority_policy)::text = ANY (ARRAY['FIFO'::character varying, 'PRIORITY'::character varying, 'FAIR_SHARE'::character varying]))),
    CONSTRAINT ck_resource_queue_type CHECK (((queue_type)::text = ANY (ARRAY['IMPORT'::character varying, 'EXPORT'::character varying, 'DISPATCH'::character varying, 'MIXED'::character varying]))),
    CONSTRAINT resource_queue_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.resource_queue IS '租户资源队列配置；定义并发、QPS、公平调度和配额边界。';


COMMENT ON COLUMN batch.resource_queue.queue_type IS '队列类型；决定资源调度策略。';


COMMENT ON COLUMN batch.resource_queue.priority_policy IS '队列内优先级排序策略。';


COMMENT ON COLUMN batch.resource_queue.fair_share_weight IS '公平调度权重。';


COMMENT ON COLUMN batch.resource_queue.burst_limit IS '允许短时超过基础并发的突发额度。';


COMMENT ON COLUMN batch.resource_queue.quota_reset_policy IS '队列配额窗口重置策略。';


COMMENT ON COLUMN batch.resource_queue.group_shared_max_running_jobs IS '公平组共享的最大运行作业数。';


CREATE SEQUENCE batch.resource_queue_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.resource_queue_id_seq OWNED BY batch.resource_queue.id;


CREATE TABLE batch.resource_tag (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    resource_type character varying(32) NOT NULL,
    resource_code character varying(128) NOT NULL,
    tag_key character varying(64) NOT NULL,
    tag_value character varying(256) DEFAULT ''::character varying NOT NULL,
    created_by character varying(64),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_resource_tag_type CHECK (((resource_type)::text = ANY (ARRAY['JOB'::character varying, 'WORKFLOW'::character varying, 'FILE_CHANNEL'::character varying, 'FILE_TEMPLATE'::character varying]))),
    CONSTRAINT resource_tag_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.resource_tag IS '资源标签映射；用于资源匹配和运维筛选。';


COMMENT ON COLUMN batch.resource_tag.resource_type IS '被标记资源的类型。';


CREATE SEQUENCE batch.resource_tag_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.resource_tag_id_seq OWNED BY batch.resource_tag.id;


CREATE TABLE batch.retry_schedule (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    related_type character varying(32) NOT NULL,
    related_id bigint NOT NULL,
    retry_policy character varying(32) NOT NULL,
    retry_count integer DEFAULT 0 NOT NULL,
    max_retry_count integer DEFAULT 0 NOT NULL,
    next_retry_at timestamp with time zone NOT NULL,
    retry_status character varying(32) NOT NULL,
    dedup_key character varying(256) NOT NULL,
    last_error_code character varying(64),
    last_error_message character varying(1024),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    last_error_key character varying(128),
    last_error_args jsonb,
    CONSTRAINT ck_retry_schedule_max_retry_count CHECK ((max_retry_count >= 0)),
    CONSTRAINT ck_retry_schedule_policy CHECK (((retry_policy)::text = ANY (ARRAY['FIXED'::character varying, 'EXPONENTIAL'::character varying]))),
    CONSTRAINT ck_retry_schedule_related_type CHECK (((related_type)::text = ANY (ARRAY['JOB_INSTANCE'::character varying, 'JOB_PARTITION'::character varying, 'JOB_TASK'::character varying, 'PIPELINE_INSTANCE'::character varying, 'FILE_DISPATCH'::character varying]))),
    CONSTRAINT ck_retry_schedule_retry_count CHECK ((retry_count >= 0)),
    CONSTRAINT ck_retry_schedule_status CHECK (((retry_status)::text = ANY (ARRAY['WAITING'::character varying, 'RUNNING'::character varying, 'SUCCESS'::character varying, 'FAILED'::character varying, 'EXHAUSTED'::character varying, 'CANCELLED'::character varying]))),
    CONSTRAINT retry_schedule_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.retry_schedule IS '通用重试调度记录；关联业务对象并保存下一次执行时点。';


COMMENT ON COLUMN batch.retry_schedule.related_type IS '被重试的业务对象类型。';


COMMENT ON COLUMN batch.retry_schedule.retry_policy IS '重试退避策略。';


COMMENT ON COLUMN batch.retry_schedule.retry_count IS '当前已执行重试次数。';


COMMENT ON COLUMN batch.retry_schedule.max_retry_count IS '允许的最大重试次数。';


COMMENT ON COLUMN batch.retry_schedule.next_retry_at IS '下一次允许执行重试的时点。';


COMMENT ON COLUMN batch.retry_schedule.retry_status IS '重试计划状态。';


COMMENT ON COLUMN batch.retry_schedule.dedup_key IS '重试操作幂等键。';


COMMENT ON COLUMN batch.retry_schedule.last_error_key IS '同 pipeline_step_run.error_key (重试调度的最近一次)';


COMMENT ON COLUMN batch.retry_schedule.last_error_args IS '同 pipeline_step_run.error_args';


CREATE SEQUENCE batch.retry_schedule_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.retry_schedule_id_seq OWNED BY batch.retry_schedule.id;


CREATE TABLE batch.scheduled_occurrence (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    workflow_definition_id bigint NOT NULL,
    workflow_code character varying(128) NOT NULL,
    schedule_version integer NOT NULL,
    calendar_code character varying(128),
    scheduled_fire_time timestamp with time zone NOT NULL,
    biz_date date,
    occurrence_status character varying(32) DEFAULT 'PLANNED'::character varying NOT NULL,
    request_id character varying(128),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    last_rejection_reason character varying(512),
    misfire_detected_at timestamp with time zone,
    readiness_deferred_since timestamp with time zone,
    readiness_deadline timestamp with time zone,
    CONSTRAINT ck_scheduled_occurrence_closed_rejection CHECK ((((occurrence_status)::text <> 'CLOSED_DAY_REJECTED'::text) OR ((last_rejection_reason IS NOT NULL) AND (request_id IS NULL)))),
    CONSTRAINT ck_scheduled_occurrence_status CHECK (((occurrence_status)::text = ANY (ARRAY['PLANNED'::character varying, 'REQUESTED'::character varying, 'WAITING_READINESS'::character varying, 'MISFIRE_PENDING'::character varying, 'SKIPPED_CALENDAR'::character varying, 'MISFIRE_SKIPPED'::character varying, 'READINESS_TIMEOUT'::character varying, 'CLOSED_DAY_REJECTED'::character varying, 'SUPERSEDED'::character varying]))),
    CONSTRAINT scheduled_occurrence_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.scheduled_occurrence IS 'Quartz 计划触发的投影记录，用于核对预期工作；属于 Trigger 控制面状态，不是请求或执行记录。';


COMMENT ON COLUMN batch.scheduled_occurrence.schedule_version IS '生成此 Quartz 触发投影时使用的 Workflow 调度版本。';


COMMENT ON COLUMN batch.scheduled_occurrence.scheduled_fire_time IS '原始 Quartz 计划触发身份，精确到 epoch 毫秒；与定时请求去重键一致。';


COMMENT ON COLUMN batch.scheduled_occurrence.biz_date IS '由共享 Trigger 日历解析器计算；NULL 表示日历解析结果为 SKIP。';


COMMENT ON COLUMN batch.scheduled_occurrence.occurrence_status IS '预期触发的生命周期状态，与 TriggerRequest 和 WorkflowRun 状态相互独立。';


COMMENT ON COLUMN batch.scheduled_occurrence.last_rejection_reason IS '观测到的定时触发被拒绝的原因；后续显式补跑接受同一触发身份时仍保留此原因。';


COMMENT ON COLUMN batch.scheduled_occurrence.misfire_detected_at IS '首次持久化记录 Quartz misfire 被发现的时刻；与恢复请求绑定后仍保留，API 启动调用方不得提供此值。';


COMMENT ON COLUMN batch.scheduled_occurrence.readiness_deferred_since IS 'readiness 首次等待的开始时间（原 fire 身份保持不变，仅用于计算等待窗口）。';


COMMENT ON COLUMN batch.scheduled_occurrence.readiness_deadline IS 'readiness 等待截止时间（deferred_since + readiness-window-seconds）；超窗后由扫尾器终结为 READINESS_TIMEOUT。';


CREATE TABLE batch.scheduled_occurrence_catch_up_audit (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    workflow_definition_id bigint NOT NULL,
    scheduled_fire_time timestamp with time zone NOT NULL,
    biz_date date NOT NULL,
    request_id character varying(128) NOT NULL,
    requested_by character varying(128) NOT NULL,
    reason character varying(1024) NOT NULL,
    created_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT scheduled_occurrence_catch_up_audit_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.scheduled_occurrence_catch_up_audit IS '将被拒绝的定时触发与显式 CATCH_UP 请求关联起来的不可变操作审计。';


CREATE SEQUENCE batch.scheduled_occurrence_catch_up_audit_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.scheduled_occurrence_catch_up_audit_id_seq OWNED BY batch.scheduled_occurrence_catch_up_audit.id;


CREATE TABLE batch.scheduled_occurrence_coverage (
    tenant_id character varying(64) NOT NULL,
    workflow_definition_id bigint NOT NULL,
    schedule_version integer NOT NULL,
    calendar_code character varying(128),
    coverage_from_at timestamp with time zone NOT NULL,
    coverage_through_at timestamp with time zone NOT NULL,
    last_success_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    configuration_revision bigint DEFAULT 0 NOT NULL,
    CONSTRAINT pk_scheduled_occurrence_coverage PRIMARY KEY (tenant_id, workflow_definition_id)
);


COMMENT ON TABLE batch.scheduled_occurrence_coverage IS '最近一次成功投影 Quartz 触发记录的覆盖水位；缺失或调度版本过期时禁止结算空日。';


COMMENT ON COLUMN batch.scheduled_occurrence_coverage.schedule_version IS '记录的覆盖区间内已完整投影的 Workflow 调度版本。';


COMMENT ON COLUMN batch.scheduled_occurrence_coverage.configuration_revision IS '此 occurrence 投影水位完整覆盖的租户配置修订号。';


CREATE TABLE batch.scheduled_occurrence_fire (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    workflow_definition_id bigint NOT NULL,
    scheduled_fire_time timestamp with time zone NOT NULL,
    fire_instance_id character varying(95) NOT NULL,
    refire_count integer NOT NULL,
    actual_fire_time timestamp with time zone NOT NULL,
    fire_kind character varying(32) NOT NULL,
    CONSTRAINT ck_scheduled_occurrence_fire_kind CHECK (((fire_kind)::text = ANY (ARRAY['SCHEDULED'::character varying, 'CATCH_UP'::character varying, 'MISFIRE_APPROVAL'::character varying, 'READINESS_RETRY'::character varying]))),
    CONSTRAINT scheduled_occurrence_fire_pkey PRIMARY KEY (id)
);

ALTER TABLE ONLY batch.scheduled_occurrence_fire FORCE ROW LEVEL SECURITY;


COMMENT ON TABLE batch.scheduled_occurrence_fire IS 'Quartz 回调的实际触发观察记录；可有多次重试，历史缺失和观察写入失败不会回填。';


CREATE SEQUENCE batch.scheduled_occurrence_fire_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.scheduled_occurrence_fire_id_seq OWNED BY batch.scheduled_occurrence_fire.id;


CREATE SEQUENCE batch.scheduled_occurrence_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.scheduled_occurrence_id_seq OWNED BY batch.scheduled_occurrence.id;


CREATE TABLE batch.scheduled_occurrence_supersession_history (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    workflow_definition_id bigint NOT NULL,
    scheduled_fire_time timestamp with time zone NOT NULL,
    from_status character varying(32) NOT NULL,
    to_status character varying(32) NOT NULL,
    schedule_version integer NOT NULL,
    calendar_code character varying(128),
    biz_date date,
    request_id character varying(128),
    changed_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT ck_scheduled_occurrence_history_transition CHECK ((((from_status)::text = 'SUPERSEDED'::text) OR ((to_status)::text = 'SUPERSEDED'::text))),
    CONSTRAINT scheduled_occurrence_supersession_history_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.scheduled_occurrence_supersession_history IS '记录计划变更后定时 occurrence 被替代或重新激活的不可变审计信息。';


COMMENT ON COLUMN batch.scheduled_occurrence_supersession_history.from_status IS '替代或重新激活状态转换前的 occurrence 状态。';


COMMENT ON COLUMN batch.scheduled_occurrence_supersession_history.to_status IS '替代或重新激活状态转换后的 occurrence 状态。';


COMMENT ON COLUMN batch.scheduled_occurrence_supersession_history.schedule_version IS '与该状态转换关联的 Workflow 调度版本。';


CREATE SEQUENCE batch.scheduled_occurrence_supersession_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.scheduled_occurrence_supersession_history_id_seq OWNED BY batch.scheduled_occurrence_supersession_history.id;


CREATE TABLE batch.secret_version (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    secret_ref character varying(128) NOT NULL,
    secret_name character varying(256) NOT NULL,
    version_no integer DEFAULT 1 NOT NULL,
    secret_status character varying(32) DEFAULT 'DRAFT'::character varying NOT NULL,
    current_version boolean DEFAULT false NOT NULL,
    rotation_window_start_at timestamp with time zone,
    rotation_window_end_at timestamp with time zone,
    effective_from_at timestamp with time zone,
    effective_to_at timestamp with time zone,
    secret_payload jsonb,
    rotation_reason character varying(512),
    created_by character varying(64),
    updated_by character varying(64),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    revoked_at timestamp with time zone,
    revoked_by character varying(64),
    revoke_reason character varying(512),
    CONSTRAINT ck_secret_version_payload_protected CHECK (((secret_payload IS NULL) OR ((jsonb_typeof(secret_payload) = 'object'::text) AND (secret_payload ? 'format'::text) AND ((secret_payload ->> 'format'::text) = 'BATCHENC_BASE64_V1'::text) AND (secret_payload ? 'ciphertext'::text) AND (length((secret_payload ->> 'ciphertext'::text)) >= 32)))),
    CONSTRAINT ck_secret_version_status CHECK (((secret_status)::text = ANY (ARRAY['DRAFT'::character varying, 'PUBLISHED'::character varying, 'GRAY'::character varying, 'ROLLED_BACK'::character varying]))),
    CONSTRAINT ck_secret_version_version CHECK ((version_no > 0)),
    CONSTRAINT secret_version_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.secret_version IS '密钥版本元数据；密钥材料采用受控加密载荷或外部引用。';


COMMENT ON COLUMN batch.secret_version.secret_ref IS '外部密钥或 KMS 引用。';


COMMENT ON COLUMN batch.secret_version.secret_name IS '密钥逻辑名称，不包含明文。';


COMMENT ON COLUMN batch.secret_version.version_no IS '密钥版本序号。';


COMMENT ON COLUMN batch.secret_version.secret_status IS '密钥版本状态。';


COMMENT ON COLUMN batch.secret_version.current_version IS '是否为当前可用版本。';


COMMENT ON COLUMN batch.secret_version.rotation_window_start_at IS '密钥轮换窗口开始时点。';


COMMENT ON COLUMN batch.secret_version.rotation_window_end_at IS '密钥轮换窗口结束时点。';


COMMENT ON COLUMN batch.secret_version.secret_payload IS '受保护的密钥载荷；禁止明文读取或日志输出。';


COMMENT ON COLUMN batch.secret_version.revoked_at IS '紧急撤销时点；Dispatch 每次外发前检查，覆盖实例固定版本。';


COMMENT ON COLUMN batch.secret_version.revoked_by IS '执行密钥撤销的操作者。';


COMMENT ON COLUMN batch.secret_version.revoke_reason IS '密钥撤销原因；不得包含密钥材料。';


CREATE SEQUENCE batch.secret_version_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.secret_version_id_seq OWNED BY batch.secret_version.id;


CREATE TABLE batch.shedlock (
    name character varying(64) NOT NULL,
    lock_until timestamp without time zone NOT NULL,
    locked_at timestamp without time zone NOT NULL,
    locked_by character varying(255) NOT NULL,
    CONSTRAINT pk_shedlock PRIMARY KEY (name)
);


COMMENT ON TABLE batch.shedlock IS 'ShedLock 分布式定时任务锁表；框架维护，不承载业务状态。';


CREATE TABLE batch.stateful_backend_binding (
    feature_key character varying(160) NOT NULL,
    backend character varying(64) NOT NULL,
    backend_identity character varying(1024) NOT NULL,
    generation bigint DEFAULT 0 NOT NULL,
    last_cutover_id character varying(160),
    updated_by character varying(160) NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT stateful_backend_binding_pkey PRIMARY KEY (feature_key)
);


COMMENT ON TABLE batch.stateful_backend_binding IS '有状态插件功能当前使用的后端及位置身份；应用启动时会校验该绑定。';


CREATE TABLE batch.stateful_backend_cutover_history (
    id bigint NOT NULL,
    feature_key character varying(160) NOT NULL,
    generation bigint NOT NULL,
    action character varying(32) NOT NULL,
    previous_backend character varying(64),
    previous_backend_identity character varying(1024),
    target_backend character varying(64) NOT NULL,
    target_backend_identity character varying(1024) NOT NULL,
    cutover_id character varying(160),
    changed_by character varying(160) NOT NULL,
    changed_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_stateful_backend_cutover_action CHECK (((action)::text = ANY (ARRAY['BASELINE'::character varying, 'CUTOVER'::character varying]))),
    CONSTRAINT stateful_backend_cutover_history_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.stateful_backend_cutover_history IS '不可变的基线和切换审计记录；每项功能的 cutover_id 仅允许使用一次。';


ALTER TABLE batch.stateful_backend_cutover_history ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME batch.stateful_backend_cutover_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


CREATE TABLE batch.step_registry (
    id bigint NOT NULL,
    module character varying(32) NOT NULL,
    impl_code character varying(128) NOT NULL,
    impl_class character varying(256) NOT NULL,
    registered_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    last_seen_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_step_registry_module CHECK (((module)::text = ANY (ARRAY['IMPORT'::character varying, 'EXPORT'::character varying, 'DISPATCH'::character varying, 'PROCESS'::character varying, 'ORCHESTRATOR'::character varying]))),
    CONSTRAINT step_registry_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.step_registry IS '应用启动期上报 Step bean 清单；Excel 配置上传按此白名单校验 impl_code';


COMMENT ON COLUMN batch.step_registry.module IS '所属模块：IMPORT / EXPORT / DISPATCH / PROCESS / ORCHESTRATOR';


COMMENT ON COLUMN batch.step_registry.impl_code IS 'Spring bean name，对应 pipeline_step_definition.impl_code';


COMMENT ON COLUMN batch.step_registry.impl_class IS 'Step 实现类全限定名';


COMMENT ON COLUMN batch.step_registry.last_seen_at IS '最近一次应用启动登记的时间（快照策略下每次启动都会更新）';


CREATE SEQUENCE batch.step_registry_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.step_registry_id_seq OWNED BY batch.step_registry.id;


CREATE TABLE batch.subscription_rule (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    rule_name character varying(128) NOT NULL,
    channel_code character varying(64) NOT NULL,
    event_types character varying(512) NOT NULL,
    severity_filter character varying(128),
    job_code_filter character varying(512),
    enabled boolean DEFAULT true NOT NULL,
    created_by character varying(64),
    updated_by character varying(64),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT subscription_rule_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.subscription_rule IS '通知订阅规则；按事件、级别和作业筛选通知。';


COMMENT ON COLUMN batch.subscription_rule.event_types IS '订阅的事件类型集合。';


CREATE SEQUENCE batch.subscription_rule_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.subscription_rule_id_seq OWNED BY batch.subscription_rule.id;


CREATE TABLE batch.system_parameter (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    param_key character varying(128) NOT NULL,
    param_value character varying(2048) NOT NULL,
    description character varying(512),
    created_by character varying(64),
    updated_by character varying(64),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT system_parameter_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.system_parameter IS '系统或租户参数键值配置。';


CREATE SEQUENCE batch.system_parameter_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.system_parameter_id_seq OWNED BY batch.system_parameter.id;


CREATE TABLE batch.tenant (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    tenant_name character varying(256) NOT NULL,
    status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    description character varying(512),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by character varying(128),
    CONSTRAINT ck_tenant_status CHECK (((status)::text = ANY (ARRAY['ACTIVE'::character varying, 'SUSPENDED'::character varying]))),
    CONSTRAINT tenant_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.tenant IS '平台租户主数据；所有业务数据隔离的根标识。';


COMMENT ON COLUMN batch.tenant.status IS '租户启用、停用或冻结状态。';


CREATE TABLE batch.tenant_configuration_revision (
    tenant_id text NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    updated_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT ck_tenant_configuration_revision_nonnegative CHECK ((revision >= 0)),
    CONSTRAINT tenant_configuration_revision_pkey PRIMARY KEY (tenant_id)
);


COMMENT ON TABLE batch.tenant_configuration_revision IS '租户配置写入版本，用于配置包预览与 Apply 的并发冲突检测。';


COMMENT ON COLUMN batch.tenant_configuration_revision.tenant_id IS '版本所属租户；只保存并发栅栏元数据，不包含业务配置。';


COMMENT ON COLUMN batch.tenant_configuration_revision.revision IS '租户配置任一受管表发生写入时单调递增。';


CREATE SEQUENCE batch.tenant_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.tenant_id_seq OWNED BY batch.tenant.id;


CREATE TABLE batch.tenant_quota_policy (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    policy_code character varying(128) NOT NULL,
    max_running_jobs_per_tenant integer DEFAULT 0 NOT NULL,
    max_partitions_per_tenant integer DEFAULT 0 NOT NULL,
    max_qps_per_tenant integer DEFAULT 0 NOT NULL,
    fair_share_weight integer DEFAULT 1 NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    description character varying(512),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    fair_share_group character varying(128),
    burst_limit integer DEFAULT 0 NOT NULL,
    partition_burst_limit integer DEFAULT 0 NOT NULL,
    quota_reset_policy character varying(32) DEFAULT 'NONE'::character varying NOT NULL,
    group_shared_max_running_jobs integer DEFAULT 0 NOT NULL,
    exceeded_strategy character varying(32) DEFAULT 'REJECT'::character varying NOT NULL,
    CONSTRAINT ck_tenant_quota_exceeded_strategy CHECK (((exceeded_strategy)::text = ANY (ARRAY['REJECT'::character varying, 'QUEUE_DEFER'::character varying, 'DEGRADE_PRIORITY'::character varying]))),
    CONSTRAINT ck_tenant_quota_jobs CHECK ((max_running_jobs_per_tenant >= 0)),
    CONSTRAINT ck_tenant_quota_partitions CHECK ((max_partitions_per_tenant >= 0)),
    CONSTRAINT ck_tenant_quota_qps CHECK ((max_qps_per_tenant >= 0)),
    CONSTRAINT ck_tenant_quota_weight CHECK ((fair_share_weight > 0)),
    CONSTRAINT tenant_quota_policy_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.tenant_quota_policy IS '租户配额策略；控制运行作业、分片和突发上限。';


COMMENT ON COLUMN batch.tenant_quota_policy.policy_code IS '租户配额策略稳定编码。';


COMMENT ON COLUMN batch.tenant_quota_policy.max_running_jobs_per_tenant IS '租户最大并发作业数。';


COMMENT ON COLUMN batch.tenant_quota_policy.max_partitions_per_tenant IS '租户最大并发分片数。';


COMMENT ON COLUMN batch.tenant_quota_policy.max_qps_per_tenant IS '租户动作最大 QPS。';


COMMENT ON COLUMN batch.tenant_quota_policy.fair_share_weight IS '租户公平调度权重。';


COMMENT ON COLUMN batch.tenant_quota_policy.burst_limit IS '租户作业突发额度。';


COMMENT ON COLUMN batch.tenant_quota_policy.partition_burst_limit IS '租户分片突发额度。';


COMMENT ON COLUMN batch.tenant_quota_policy.quota_reset_policy IS '租户配额窗口重置策略。';


COMMENT ON COLUMN batch.tenant_quota_policy.exceeded_strategy IS 'V89: quota 触顶处置策略 — REJECT|QUEUE_DEFER|DEGRADE_PRIORITY';


CREATE SEQUENCE batch.tenant_quota_policy_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.tenant_quota_policy_id_seq OWNED BY batch.tenant_quota_policy.id;


CREATE TABLE batch.tenant_scheduler_snapshot (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    snapshot_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    fair_share_group character varying(128),
    policy_code character varying(128),
    active_jobs integer DEFAULT 0 NOT NULL,
    active_partitions integer DEFAULT 0 NOT NULL,
    max_jobs_base integer,
    burst_limit integer,
    effective_job_cap integer,
    group_active_jobs integer,
    group_max_jobs integer,
    quota_reset_policy character varying(32),
    online_workers integer,
    detail_json jsonb,
    CONSTRAINT tenant_scheduler_snapshot_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.tenant_scheduler_snapshot IS '公平调度计算快照；用于解释调度决策和容量诊断。';


COMMENT ON COLUMN batch.tenant_scheduler_snapshot.policy_code IS '快照中的配额策略编码。';


COMMENT ON COLUMN batch.tenant_scheduler_snapshot.quota_reset_policy IS '快照中的配额重置策略。';


COMMENT ON COLUMN batch.tenant_scheduler_snapshot.detail_json IS '租户调度策略快照详情 JSON。';


CREATE SEQUENCE batch.tenant_scheduler_snapshot_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.tenant_scheduler_snapshot_id_seq OWNED BY batch.tenant_scheduler_snapshot.id;


CREATE TABLE batch.trigger_launch_recovery (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    request_id character varying(128) NOT NULL,
    envelope_ciphertext text NOT NULL,
    trace_id character varying(128),
    attempts integer DEFAULT 0 NOT NULL,
    next_attempt_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    recovery_status character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    last_error_code character varying(128),
    last_error_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    recovery_generation bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_trigger_launch_recovery_status CHECK (((recovery_status)::text = ANY (ARRAY['PENDING'::character varying, 'DONE'::character varying, 'REJECTED'::character varying, 'DEAD'::character varying]))),
    CONSTRAINT trigger_launch_recovery_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.trigger_launch_recovery IS 'Kafka 消费有限重试耗尽后的启动意图；由周期扫描幂等重投，重试有上限并保留最后错误码。';


COMMENT ON COLUMN batch.trigger_launch_recovery.envelope_ciphertext IS '原始 launch envelope 的密文（SecretPayloadProtector），避免敏感参数明文落库。';


COMMENT ON COLUMN batch.trigger_launch_recovery.trace_id IS '触发链路的 traceId，便于按链路排查恢复过程。';


COMMENT ON COLUMN batch.trigger_launch_recovery.attempts IS '已重投次数；达到上限后置为 DEAD 并停止重投。';


COMMENT ON COLUMN batch.trigger_launch_recovery.next_attempt_at IS '下一次重投的可用时间（退避后）；扫描只处理已到期的 PENDING 记录。';


COMMENT ON COLUMN batch.trigger_launch_recovery.recovery_status IS 'PENDING=待重投 / DONE=已重投成功 / REJECTED=业务级永久拒绝 / DEAD=超过重试上限。';


COMMENT ON COLUMN batch.trigger_launch_recovery.last_error_code IS '最近一次重投失败的错误码（HTTP 状态或异常类名），用于运维定位。';


COMMENT ON COLUMN batch.trigger_launch_recovery.recovery_generation IS '死信重新入队时递增，隔离旧恢复调用的迟到失败回执。';


CREATE SEQUENCE batch.trigger_launch_recovery_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.trigger_launch_recovery_id_seq OWNED BY batch.trigger_launch_recovery.id;


CREATE TABLE batch.trigger_misfire_pending (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    job_code character varying(128),
    scheduled_fire_time timestamp with time zone NOT NULL,
    detected_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    status character varying(32) DEFAULT 'PENDING'::character varying NOT NULL,
    approved_by character varying(64),
    approved_at timestamp with time zone,
    rejection_reason character varying(512),
    catch_up_request_id bigint,
    expires_at timestamp with time zone DEFAULT (CURRENT_TIMESTAMP + '7 days'::interval) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    workflow_definition_id bigint,
    CONSTRAINT ck_trigger_misfire_pending_status CHECK (((status)::text = ANY (ARRAY['PENDING'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying, 'EXPIRED'::character varying]))),
    CONSTRAINT ck_trigger_misfire_pending_workflow_present CHECK ((workflow_definition_id IS NOT NULL)),
    CONSTRAINT trigger_misfire_pending_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.trigger_misfire_pending IS '需人工审批的 misfire 待处理记录；审批后生成补跑 fire。';


COMMENT ON COLUMN batch.trigger_misfire_pending.status IS 'PENDING=待审 / APPROVED=已批准并补 fire / REJECTED=运维拒绝 / EXPIRED=超 7d 自动归档';


COMMENT ON COLUMN batch.trigger_misfire_pending.expires_at IS '默认 7 天过期;过期后周期任务把 PENDING 改 EXPIRED';


CREATE SEQUENCE batch.trigger_misfire_pending_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.trigger_misfire_pending_id_seq OWNED BY batch.trigger_misfire_pending.id;


CREATE TABLE batch.trigger_request (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    request_id character varying(128) NOT NULL,
    trigger_type character varying(32) NOT NULL,
    job_code character varying(128),
    biz_date date,
    dedup_key character varying(256) NOT NULL,
    request_payload_hash character varying(128),
    request_status character varying(32) NOT NULL,
    related_job_instance_id bigint,
    trace_id character varying(128),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    forward_retry_count integer DEFAULT 0 NOT NULL,
    dry_run boolean DEFAULT false NOT NULL,
    parent_workflow_run_id bigint,
    parent_node_code character varying(128),
    workflow_definition_id bigint,
    external_event_code character varying(128),
    external_event_source character varying(128),
    external_event_id character varying(256),
    launch_payload jsonb,
    CONSTRAINT ck_trigger_request_external_event_identity CHECK (((external_event_code IS NULL) OR (((trigger_type)::text = 'EVENT'::text) AND (length(btrim((external_event_code)::text)) > 0)))),
    CONSTRAINT ck_trigger_request_external_source_identity CHECK ((((external_event_source IS NULL) AND (external_event_id IS NULL)) OR (((trigger_type)::text = 'EVENT'::text) AND (external_event_code IS NOT NULL) AND (external_event_source IS NOT NULL) AND (external_event_id IS NOT NULL) AND (length(btrim((external_event_source)::text)) > 0) AND (length(btrim((external_event_id)::text)) > 0)))),
    CONSTRAINT ck_trigger_request_status CHECK (((request_status)::text = ANY (ARRAY['PENDING'::character varying, 'PROCESSING'::character varying, 'ACCEPTED'::character varying, 'WAITING'::character varying, 'DUPLICATE'::character varying, 'REJECTED'::character varying, 'LAUNCHED'::character varying, 'FORWARD_FAILED'::character varying, 'GIVE_UP'::character varying]))),
    CONSTRAINT ck_trigger_request_target CHECK (((job_code IS NOT NULL) <> (workflow_definition_id IS NOT NULL))),
    CONSTRAINT ck_trigger_request_type CHECK (((trigger_type)::text = ANY (ARRAY['API'::character varying, 'MANUAL'::character varying, 'EVENT'::character varying, 'CATCH_UP'::character varying, 'SCHEDULED'::character varying, 'RERUN'::character varying]))),
    CONSTRAINT trigger_request_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.trigger_request IS '触发请求幂等记录；Trigger 写入后由 Orchestrator 转换为作业启动。';


COMMENT ON COLUMN batch.trigger_request.request_id IS '外部或 Console 提供的请求标识。';


COMMENT ON COLUMN batch.trigger_request.trigger_type IS '触发来源类型。';


COMMENT ON COLUMN batch.trigger_request.dedup_key IS '触发幂等键；重复请求不得重复启动实例。';


COMMENT ON COLUMN batch.trigger_request.request_payload_hash IS '请求载荷哈希；用于幂等冲突诊断，不保存原始敏感载荷。';


COMMENT ON COLUMN batch.trigger_request.request_status IS '触发请求处理状态机。';


COMMENT ON COLUMN batch.trigger_request.related_job_instance_id IS '成功启动后关联的 job_instance 标识。';


COMMENT ON COLUMN batch.trigger_request.trace_id IS '触发链路标识。';


COMMENT ON COLUMN batch.trigger_request.forward_retry_count IS '转发到 Orchestrator 的已尝试次数。';


COMMENT ON COLUMN batch.trigger_request.dry_run IS 'ADR-026 dry-run 演练；trigger 入口标记，落到 LaunchRequest.dryRun → 后续 instance/partition/task 链';


COMMENT ON COLUMN batch.trigger_request.parent_workflow_run_id IS '内部 Job 节点启动所属的 WorkflowRun；顶层触发时为空。';


COMMENT ON COLUMN batch.trigger_request.parent_node_code IS '内部 Job 节点启动所属的 Workflow JOB 节点编码。';


COMMENT ON COLUMN batch.trigger_request.workflow_definition_id IS '顶层触发指定的 Workflow 目标；仅由 parent_workflow_run_id 所属的内部 Job 节点请求为空。';


COMMENT ON COLUMN batch.trigger_request.external_event_code IS 'EVENT 请求的外部事件身份；其他启动来源及内部 EVENT 请求为空。';


COMMENT ON COLUMN batch.trigger_request.external_event_source IS '发出事件的外部系统稳定身份。';


COMMENT ON COLUMN batch.trigger_request.external_event_id IS '在 external_event_source 范围内唯一的稳定事件标识。';


COMMENT ON COLUMN batch.trigger_request.launch_payload IS '原始受理的 LaunchRequest；审批和恢复时复用此载荷，不重新计算日期、区间或配置版本。';


CREATE SEQUENCE batch.trigger_request_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.trigger_request_id_seq OWNED BY batch.trigger_request.id;


CREATE TABLE batch.trigger_request_idempotency_alias (
    tenant_id character varying(64) NOT NULL,
    idempotency_key character varying(256) NOT NULL,
    request_id character varying(128) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_trigger_request_idempotency_alias PRIMARY KEY (tenant_id, idempotency_key)
);


COMMENT ON TABLE batch.trigger_request_idempotency_alias IS '额外的调用方幂等键，解析到按外部事件身份去重的请求。';


COMMENT ON COLUMN batch.trigger_request_idempotency_alias.idempotency_key IS '租户范围内的调用方重试键；将已受理的外部事件投递解析到原业务请求 ID。';


CREATE TABLE batch.waiting_partition_scan_cursor (
    singleton_id smallint NOT NULL,
    last_partition_id bigint DEFAULT 0 NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    cycle_upper_id bigint DEFAULT 0 NOT NULL,
    cycle_dry_run boolean DEFAULT false NOT NULL,
    CONSTRAINT ck_waiting_scan_cycle_bounds CHECK ((cycle_upper_id >= last_partition_id)),
    CONSTRAINT waiting_partition_scan_cursor_last_partition_id_check CHECK ((last_partition_id >= 0)),
    CONSTRAINT waiting_partition_scan_cursor_singleton_id_check CHECK ((singleton_id = 1)),
    CONSTRAINT waiting_partition_scan_cursor_version_check CHECK ((version >= 0)),
    CONSTRAINT waiting_partition_scan_cursor_pkey PRIMARY KEY (singleton_id)
);


COMMENT ON TABLE batch.waiting_partition_scan_cursor IS '等待分片全局扫描的共享轮转位置；不存放租户业务数据。';


COMMENT ON COLUMN batch.waiting_partition_scan_cursor.singleton_id IS '固定为 1 的单例键。';


COMMENT ON COLUMN batch.waiting_partition_scan_cursor.last_partition_id IS '上一批扫描末尾分片 ID；0 表示从头扫描。';


COMMENT ON COLUMN batch.waiting_partition_scan_cursor.version IS '扫描位置的乐观并发版本。';


COMMENT ON COLUMN batch.waiting_partition_scan_cursor.updated_at IS '扫描位置最近更新时刻。';


COMMENT ON COLUMN batch.waiting_partition_scan_cursor.cycle_upper_id IS '当前扫描轮次的分片 ID 上界；0 表示开始新轮次。';


COMMENT ON COLUMN batch.waiting_partition_scan_cursor.cycle_dry_run IS '当前轮次是否正在扫描演练分片；正式分片阶段为 false。';


CREATE TABLE batch.webhook_delivery_log (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    subscription_id bigint NOT NULL,
    event_type character varying(64) NOT NULL,
    payload_json jsonb NOT NULL,
    http_status integer,
    response_body character varying(2048),
    delivery_status character varying(32) DEFAULT 'PENDING'::character varying NOT NULL,
    attempt integer DEFAULT 0 NOT NULL,
    next_retry_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    terminal_event_key character varying(256),
    delivery_claim_token uuid,
    delivery_claim_until timestamp with time zone,
    CONSTRAINT ck_webhook_delivery_status CHECK (((delivery_status)::text = ANY (ARRAY['PENDING'::character varying, 'SUCCESS'::character varying, 'FAILED'::character varying, 'EXHAUSTED'::character varying, 'GIVE_UP'::character varying]))),
    CONSTRAINT webhook_delivery_log_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.webhook_delivery_log IS 'Webhook 调用尝试和重试诊断。';


COMMENT ON COLUMN batch.webhook_delivery_log.event_type IS 'Webhook 对应的业务事件类型。';


COMMENT ON COLUMN batch.webhook_delivery_log.payload_json IS 'Webhook 请求载荷 JSON。';


COMMENT ON COLUMN batch.webhook_delivery_log.http_status IS '下游 HTTP 响应状态码。';


COMMENT ON COLUMN batch.webhook_delivery_log.delivery_status IS 'Webhook 投递状态。';


COMMENT ON COLUMN batch.webhook_delivery_log.next_retry_at IS '下一次 Webhook 重试时点。';


COMMENT ON COLUMN batch.webhook_delivery_log.terminal_event_key IS '可靠 Workflow 终态投递的稳定事件身份。';


COMMENT ON COLUMN batch.webhook_delivery_log.delivery_claim_token IS '本轮投递认领 token，回写必须匹配。';


COMMENT ON COLUMN batch.webhook_delivery_log.delivery_claim_until IS '认领租约截止时间，过期可恢复。';


CREATE SEQUENCE batch.webhook_delivery_log_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.webhook_delivery_log_id_seq OWNED BY batch.webhook_delivery_log.id;


CREATE TABLE batch.webhook_subscription (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    name character varying(128) NOT NULL,
    callback_url character varying(1024) NOT NULL,
    event_types character varying(512) NOT NULL,
    secret character varying(256),
    enabled boolean DEFAULT true NOT NULL,
    created_by character varying(64),
    updated_by character varying(64),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    is_deleted boolean DEFAULT false NOT NULL,
    CONSTRAINT webhook_subscription_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.webhook_subscription IS 'Webhook 订阅配置；回调端点经过 SSRF 安全校验。';


COMMENT ON COLUMN batch.webhook_subscription.callback_url IS 'Webhook 回调地址；写入前必须经过 SSRF 校验。';


COMMENT ON COLUMN batch.webhook_subscription.event_types IS '订阅的事件类型集合。';


COMMENT ON COLUMN batch.webhook_subscription.secret IS 'Webhook 签名密钥的受保护值或引用。';


COMMENT ON COLUMN batch.webhook_subscription.is_deleted IS '软删除标记(同上); recreate 同 name 走 upsert 自动复活';


CREATE SEQUENCE batch.webhook_subscription_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.webhook_subscription_id_seq OWNED BY batch.webhook_subscription.id;


CREATE TABLE batch.worker_registry (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    worker_code character varying(128) NOT NULL,
    worker_group character varying(128) NOT NULL,
    host_name character varying(256),
    host_ip character varying(64),
    process_id character varying(64),
    capability_tags jsonb,
    resource_tag character varying(64),
    status character varying(32) DEFAULT 'ONLINE'::character varying NOT NULL,
    heartbeat_at timestamp with time zone NOT NULL,
    last_start_at timestamp with time zone,
    version character varying(64),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    current_load integer DEFAULT 0 NOT NULL,
    drain_started_at timestamp with time zone,
    drain_deadline_at timestamp with time zone,
    max_concurrent integer DEFAULT 10 NOT NULL,
    is_self_hosted boolean DEFAULT false NOT NULL,
    build_id character varying(128),
    sdk_version character varying(64),
    worker_pool_code character varying(128),
    port integer,
    task_capabilities jsonb,
    replay_capabilities jsonb,
    CONSTRAINT ck_worker_registry_max_concurrent CHECK ((max_concurrent > 0)),
    CONSTRAINT ck_worker_registry_status CHECK (((status)::text = ANY (ARRAY['ONLINE'::character varying, 'OFFLINE'::character varying, 'DRAINING'::character varying, 'DECOMMISSIONED'::character varying]))),
    CONSTRAINT worker_registry_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.worker_registry IS '在线 worker 注册与心跳快照；调度容量判断的输入。';


COMMENT ON COLUMN batch.worker_registry.worker_group IS 'Worker 所属调度组。';


COMMENT ON COLUMN batch.worker_registry.capability_tags IS 'Worker 能力标签 JSON。';


COMMENT ON COLUMN batch.worker_registry.status IS 'Worker 在线、draining 或离线状态。';


COMMENT ON COLUMN batch.worker_registry.version IS 'Worker 注册信息乐观锁版本。';


COMMENT ON COLUMN batch.worker_registry.drain_started_at IS '收到 DRAINING 请求的时刻。';


COMMENT ON COLUMN batch.worker_registry.drain_deadline_at IS '超过此时刻后，Orchestrator 可以接管仍在执行中的任务。';


COMMENT ON COLUMN batch.worker_registry.max_concurrent IS 'V87 (2026-05-03): worker 并发上限, current_load >= max_concurrent 时 selector skip 实现反压';


COMMENT ON COLUMN batch.worker_registry.is_self_hosted IS 'ADR-035 自托管标识:true=SDK 注册的租户自托管 worker(batch-worker-sdk);false=平台代部署内建 worker(默认)';


COMMENT ON COLUMN batch.worker_registry.sdk_version IS 'Worker SDK 协议版本。';


COMMENT ON COLUMN batch.worker_registry.worker_pool_code IS '稳定的 Kafka 路由池代码；worker_code 标识一个具体运行实例';


COMMENT ON COLUMN batch.worker_registry.port IS 'Worker 实际监听 HTTP 端口；NULL=未上报（老 worker / 老 SDK / 非 web 上下文或端口未绑定）';


COMMENT ON COLUMN batch.worker_registry.task_capabilities IS 'Worker 注册时上报的执行器能力摘要，仅用于运维展示，不作为任务路由依据';


COMMENT ON COLUMN batch.worker_registry.replay_capabilities IS 'Worker 注册时上报的重放能力声明目录；未声明时按失败关闭处理。';


CREATE SEQUENCE batch.worker_registry_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.worker_registry_id_seq OWNED BY batch.worker_registry.id;


CREATE TABLE batch.worker_report_outbox (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    task_id bigint NOT NULL,
    partition_invocation_id character varying(128) DEFAULT ''::character varying NOT NULL,
    trace_id character varying(128),
    payload_json jsonb NOT NULL,
    publish_status character varying(32) NOT NULL,
    attempt_count integer NOT NULL,
    next_attempt_at bigint NOT NULL,
    created_at bigint NOT NULL,
    updated_at bigint NOT NULL,
    claim_token character varying(64),
    CONSTRAINT ck_worker_report_outbox_publish_status CHECK (((publish_status)::text = ANY (ARRAY['NEW'::character varying, 'PUBLISHING'::character varying, 'PUBLISHED'::character varying, 'FAILED'::character varying, 'GIVE_UP'::character varying]))),
    CONSTRAINT worker_report_outbox_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.worker_report_outbox IS 'Worker 本地结果回报 Outbox 的平台持久化实现。';


COMMENT ON COLUMN batch.worker_report_outbox.partition_invocation_id IS 'Worker 本地执行调用标识。';


COMMENT ON COLUMN batch.worker_report_outbox.trace_id IS 'Worker 回报链路标识。';


COMMENT ON COLUMN batch.worker_report_outbox.payload_json IS '待回报 Orchestrator 的结果载荷 JSON。';


COMMENT ON COLUMN batch.worker_report_outbox.publish_status IS '回报投递状态。';


COMMENT ON COLUMN batch.worker_report_outbox.claim_token IS '当前投递领取凭据；重新入队、陈旧回收或完成本次尝试后失效';


CREATE SEQUENCE batch.worker_report_outbox_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.worker_report_outbox_id_seq OWNED BY batch.worker_report_outbox.id;


CREATE TABLE batch.workflow_definition (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    workflow_code character varying(128) NOT NULL,
    workflow_name character varying(256) NOT NULL,
    workflow_type character varying(32) DEFAULT 'DAG'::character varying NOT NULL,
    version integer DEFAULT 1 NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    description character varying(1024),
    created_by character varying(64),
    updated_by character varying(64),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    single_job_definition_id bigint,
    schedule_type character varying(32) DEFAULT 'MANUAL'::character varying NOT NULL,
    schedule_expr character varying(256),
    timezone character varying(64),
    trigger_mode character varying(32) DEFAULT 'MANUAL'::character varying NOT NULL,
    calendar_code character varying(128),
    depends_on_job_code character varying(128),
    schedule_enabled boolean DEFAULT true NOT NULL,
    max_active_runs_per_biz_date integer DEFAULT 1 NOT NULL,
    CONSTRAINT ck_workflow_definition_cron_expr_required CHECK ((((schedule_type)::text <> ALL (ARRAY['CRON'::character varying, 'FIXED_RATE'::character varying])) OR (NULLIF(btrim((schedule_expr)::text), ''::text) IS NOT NULL))),
    CONSTRAINT ck_workflow_definition_max_active_runs CHECK (((max_active_runs_per_biz_date >= 1) AND (max_active_runs_per_biz_date <= 64))),
    CONSTRAINT ck_workflow_definition_schedule_type CHECK (((schedule_type)::text = ANY (ARRAY['CRON'::character varying, 'FIXED_RATE'::character varying, 'MANUAL'::character varying, 'EVENT'::character varying, 'ONE_TIME'::character varying]))),
    CONSTRAINT ck_workflow_definition_trigger_mode CHECK (((trigger_mode)::text = ANY (ARRAY['SCHEDULED'::character varying, 'API'::character varying, 'MANUAL'::character varying, 'EVENT'::character varying, 'MIXED'::character varying]))),
    CONSTRAINT ck_workflow_definition_type CHECK (((workflow_type)::text = ANY (ARRAY['DAG'::character varying, 'PIPELINE'::character varying, 'MIXED'::character varying]))),
    CONSTRAINT workflow_definition_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.workflow_definition IS '租户工作流 DAG 定义；Console 配置的节点和边的聚合根。';


COMMENT ON COLUMN batch.workflow_definition.workflow_type IS '工作流类型；约束可用节点和编排语义。';


COMMENT ON COLUMN batch.workflow_definition.version IS '定义乐观锁版本。';


COMMENT ON COLUMN batch.workflow_definition.single_job_definition_id IS '由平台维护的关联，指向此默认单作业 Workflow 所代表的 Job 定义；组合 Workflow 时为空。';


COMMENT ON COLUMN batch.workflow_definition.schedule_type IS 'Workflow 级调度类型；顶层触发调度由 Workflow 负责。';


COMMENT ON COLUMN batch.workflow_definition.schedule_expr IS 'Workflow 触发调度器使用的 Workflow 级调度表达式。';


COMMENT ON COLUMN batch.workflow_definition.timezone IS '用于解析 Workflow 触发时刻和业务日期的 IANA 时区。';


COMMENT ON COLUMN batch.workflow_definition.trigger_mode IS 'Workflow 触发入口模式（SCHEDULED/API/MANUAL/EVENT/MIXED）。';


COMMENT ON COLUMN batch.workflow_definition.calendar_code IS '用于解析 Workflow 触发业务日期的租户业务日历。';


COMMENT ON COLUMN batch.workflow_definition.depends_on_job_code IS 'Workflow 调度的上游 Job 就绪依赖；引用同一业务日内的上游作业。';


COMMENT ON COLUMN batch.workflow_definition.schedule_enabled IS 'Workflow 触发调度开关；独立于 workflow_definition.enabled 和 job_definition.enabled。';


COMMENT ON COLUMN batch.workflow_definition.max_active_runs_per_biz_date IS '同一租户、Workflow、业务日与执行模式允许同时存在的 CREATED/RUNNING 根运行上限；默认 1。';


CREATE SEQUENCE batch.workflow_definition_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.workflow_definition_id_seq OWNED BY batch.workflow_definition.id;


CREATE TABLE batch.workflow_definition_version (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    workflow_definition_id bigint NOT NULL,
    workflow_code character varying(128) NOT NULL,
    version integer NOT NULL,
    workflow_name character varying(256),
    workflow_type character varying(32),
    enabled boolean,
    nodes_json jsonb NOT NULL,
    edges_json jsonb NOT NULL,
    saved_by character varying(64),
    saved_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    summary character varying(512),
    schedule_type character varying(32),
    schedule_expr character varying(256),
    timezone character varying(64),
    trigger_mode character varying(32),
    calendar_code character varying(128),
    depends_on_job_code character varying(128),
    schedule_enabled boolean,
    CONSTRAINT workflow_definition_version_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.workflow_definition_version IS 'workflow-dag-designer Polish — fullUpdate 每次写入主表 version+1 后,同事务追加一行快照;FE diff 页拉历史版本';


COMMENT ON COLUMN batch.workflow_definition_version.workflow_definition_id IS '反向引用 batch.workflow_definition.id(主表)';


COMMENT ON COLUMN batch.workflow_definition_version.version IS '与主表 version 同步;每次 fullUpdate 后 += 1';


COMMENT ON COLUMN batch.workflow_definition_version.workflow_type IS '工作流定义类型。';


COMMENT ON COLUMN batch.workflow_definition_version.nodes_json IS '整 nodes 列表序列化(ObjectMapper.writeValueAsString),反序列化由 console-api 负责';


COMMENT ON COLUMN batch.workflow_definition_version.edges_json IS '整 edges 列表序列化(ObjectMapper.writeValueAsString),反序列化由 console-api 负责';


COMMENT ON COLUMN batch.workflow_definition_version.saved_by IS '触发 fullUpdate 的 console 账号(SecurityContext.username)';


COMMENT ON COLUMN batch.workflow_definition_version.summary IS '可空,描述本次改动;Spike 阶段 FE 未提交字段,留空预留';


COMMENT ON COLUMN batch.workflow_definition_version.schedule_type IS '完整 Workflow 定义版本中冻结的调度类型；V241 之前写入的快照为空。';


COMMENT ON COLUMN batch.workflow_definition_version.schedule_expr IS '完整 Workflow 定义版本中冻结的调度表达式；V241 之前写入的快照为空。';


COMMENT ON COLUMN batch.workflow_definition_version.timezone IS '完整 Workflow 定义版本中冻结的调度时区；V241 之前写入的快照为空。';


COMMENT ON COLUMN batch.workflow_definition_version.trigger_mode IS '完整 Workflow 定义版本中冻结的触发模式；V241 之前写入的快照为空。';


COMMENT ON COLUMN batch.workflow_definition_version.calendar_code IS '完整 Workflow 定义版本中冻结的业务日历；V241 之前写入的快照为空。';


COMMENT ON COLUMN batch.workflow_definition_version.depends_on_job_code IS '完整 Workflow 定义版本中冻结的旧版 Job 依赖；V241 之前写入的快照为空。';


COMMENT ON COLUMN batch.workflow_definition_version.schedule_enabled IS '此版本冻结的 Workflow 触发调度开关；V249 之前写入的快照为空。';


CREATE SEQUENCE batch.workflow_definition_version_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.workflow_definition_version_id_seq OWNED BY batch.workflow_definition_version.id;


CREATE TABLE batch.workflow_edge (
    id bigint NOT NULL,
    workflow_definition_id bigint NOT NULL,
    from_node_code character varying(128) NOT NULL,
    to_node_code character varying(128) NOT NULL,
    edge_type character varying(32) DEFAULT 'SUCCESS'::character varying NOT NULL,
    condition_expr character varying(1024),
    enabled boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    tenant_id character varying(64) NOT NULL,
    CONSTRAINT ck_workflow_edge_type CHECK (((edge_type)::text = ANY (ARRAY['SUCCESS'::character varying, 'FAILURE'::character varying, 'CONDITION'::character varying, 'ALWAYS'::character varying]))),
    CONSTRAINT workflow_edge_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.workflow_edge IS '工作流 DAG 边定义；描述节点间的控制流和条件表达式。';


COMMENT ON COLUMN batch.workflow_edge.edge_type IS '边类型；决定条件与默认推进规则。';


COMMENT ON COLUMN batch.workflow_edge.condition_expr IS '条件边表达式；由工作流解释器按受限语法计算。';


COMMENT ON COLUMN batch.workflow_edge.tenant_id IS 'V85(2026-05-03) 补充,取自 workflow_definition.tenant_id;落地 unique(tenant_id, ...) 多租隔离原则';


CREATE SEQUENCE batch.workflow_edge_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.workflow_edge_id_seq OWNED BY batch.workflow_edge.id;


CREATE TABLE batch.workflow_launch_wait (
    id bigint NOT NULL,
    tenant_id character varying(64) NOT NULL,
    request_id character varying(128) NOT NULL,
    workflow_definition_id bigint NOT NULL,
    biz_date date NOT NULL,
    dry_run boolean DEFAULT false NOT NULL,
    launch_payload jsonb NOT NULL,
    wait_status character varying(24) DEFAULT 'WAITING'::character varying NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    next_attempt_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_workflow_launch_wait_attempt CHECK ((attempt_count >= 0)),
    CONSTRAINT ck_workflow_launch_wait_status CHECK (((wait_status)::text = ANY (ARRAY['WAITING'::character varying, 'RESUMED'::character varying, 'REJECTED'::character varying]))),
    CONSTRAINT workflow_launch_wait_pkey PRIMARY KEY (id)
);

ALTER TABLE ONLY batch.workflow_launch_wait FORCE ROW LEVEL SECURITY;


COMMENT ON TABLE batch.workflow_launch_wait IS '由 Orchestrator 持久化管理的 Workflow 业务日执行名额等待；保留原请求且不阻塞 Kafka 消费。';


COMMENT ON COLUMN batch.workflow_launch_wait.launch_payload IS '原始受理的 LaunchRequest 快照；恢复同一租户/请求时直接复用，不重新构造参数或日期。';


COMMENT ON COLUMN batch.workflow_launch_wait.wait_status IS 'WAITING 表示等待执行容量；RESUMED 表示已受理或已去重；REJECTED 表示受理失败且不得恢复。';


CREATE SEQUENCE batch.workflow_launch_wait_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.workflow_launch_wait_id_seq OWNED BY batch.workflow_launch_wait.id;


CREATE TABLE batch.workflow_monitoring_policy (
    tenant_id character varying(64) NOT NULL,
    workflow_definition_id bigint NOT NULL,
    soft_runtime_seconds integer DEFAULT 0 NOT NULL,
    soft_runtime_severity character varying(16) DEFAULT 'WARN'::character varying NOT NULL,
    start_grace_seconds integer DEFAULT 0 NOT NULL,
    start_grace_severity character varying(16) DEFAULT 'WARN'::character varying NOT NULL,
    completion_deadline_local_time time without time zone,
    completion_deadline_day_offset integer DEFAULT 0 NOT NULL,
    completion_deadline_severity character varying(16) DEFAULT 'WARN'::character varying NOT NULL,
    policy_effective_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_workflow_monitoring_policy_severities CHECK ((((soft_runtime_severity)::text = ANY (ARRAY['WARN'::character varying, 'ERROR'::character varying, 'CRITICAL'::character varying])) AND ((start_grace_severity)::text = ANY (ARRAY['WARN'::character varying, 'ERROR'::character varying, 'CRITICAL'::character varying])) AND ((completion_deadline_severity)::text = ANY (ARRAY['WARN'::character varying, 'ERROR'::character varying, 'CRITICAL'::character varying])))),
    CONSTRAINT ck_workflow_monitoring_policy_thresholds CHECK (((soft_runtime_seconds >= 0) AND (start_grace_seconds >= 0) AND (completion_deadline_day_offset = ANY (ARRAY[0, 1])))),
    CONSTRAINT pk_workflow_monitoring_policy PRIMARY KEY (tenant_id, workflow_definition_id)
);

ALTER TABLE ONLY batch.workflow_monitoring_policy FORCE ROW LEVEL SECURITY;


COMMENT ON TABLE batch.workflow_monitoring_policy IS 'Workflow 根运行的软时限策略；不改变运行状态或硬超时，不继承为子 Job 策略。';


COMMENT ON COLUMN batch.workflow_monitoring_policy.tenant_id IS '策略所属租户。';


COMMENT ON COLUMN batch.workflow_monitoring_policy.workflow_definition_id IS '同租户 Workflow 定义身份。';


COMMENT ON COLUMN batch.workflow_monitoring_policy.soft_runtime_seconds IS '根运行开始后允许的总耗时秒数，包含编排等待；零为关闭。';


COMMENT ON COLUMN batch.workflow_monitoring_policy.soft_runtime_severity IS '根运行耗时超限的告警级别。';


COMMENT ON COLUMN batch.workflow_monitoring_policy.start_grace_seconds IS '真实计划或就绪资格时刻后的启动宽限秒数；零为关闭。';


COMMENT ON COLUMN batch.workflow_monitoring_policy.start_grace_severity IS '根运行开始过晚的告警级别。';


COMMENT ON COLUMN batch.workflow_monitoring_policy.completion_deadline_local_time IS '按 Workflow 时区解释的最晚完成钟点；空为关闭。';


COMMENT ON COLUMN batch.workflow_monitoring_policy.completion_deadline_day_offset IS '最晚完成日相对计划触发日的偏移，零为当日，一为次日。';


COMMENT ON COLUMN batch.workflow_monitoring_policy.completion_deadline_severity IS '根运行完成过晚的告警级别。';


COMMENT ON COLUMN batch.workflow_monitoring_policy.policy_effective_at IS '阈值或截止策略最近生效时刻；不补报此前已结束的历史运行。';


COMMENT ON COLUMN batch.workflow_monitoring_policy.created_at IS '策略首次保存时刻。';


COMMENT ON COLUMN batch.workflow_monitoring_policy.updated_at IS '策略最近保存时刻。';


CREATE TABLE batch.workflow_node (
    id bigint NOT NULL,
    workflow_definition_id bigint NOT NULL,
    node_code character varying(128) NOT NULL,
    node_name character varying(256) NOT NULL,
    node_type character varying(32) NOT NULL,
    related_job_code character varying(128),
    worker_group character varying(128),
    window_code character varying(128),
    node_order integer DEFAULT 0 NOT NULL,
    retry_policy character varying(32) DEFAULT 'NONE'::character varying NOT NULL,
    retry_max_count integer DEFAULT 0 NOT NULL,
    timeout_seconds integer DEFAULT 0 NOT NULL,
    node_params jsonb,
    enabled boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    tenant_id character varying(64) NOT NULL,
    cross_day_dependencies jsonb,
    cross_day_dependency_timeout_seconds integer DEFAULT 86400 NOT NULL,
    task_timeout_seconds integer,
    CONSTRAINT ck_workflow_node_cross_day_timeout CHECK ((cross_day_dependency_timeout_seconds >= 0)),
    CONSTRAINT ck_workflow_node_order CHECK ((node_order >= 0)),
    CONSTRAINT ck_workflow_node_retry_max_count CHECK ((retry_max_count >= 0)),
    CONSTRAINT ck_workflow_node_retry_policy CHECK (((retry_policy)::text = ANY (ARRAY['NONE'::character varying, 'FIXED'::character varying, 'EXPONENTIAL'::character varying]))),
    CONSTRAINT ck_workflow_node_timeout_seconds CHECK ((timeout_seconds >= 0)),
    CONSTRAINT ck_workflow_node_type CHECK (((node_type)::text = ANY (ARRAY['TASK'::character varying, 'GATEWAY'::character varying, 'START'::character varying, 'END'::character varying, 'WAIT'::character varying, 'JOB'::character varying]))),
    CONSTRAINT workflow_node_pkey PRIMARY KEY (id)
);


COMMENT ON TABLE batch.workflow_node IS 'Workflow 节点定义；ADR-018 起新增 cross_day_dependencies / cross_day_dependency_timeout_seconds 支持跨批量日依赖';


COMMENT ON COLUMN batch.workflow_node.node_type IS '节点类型；决定任务、传感器或控制逻辑。';


COMMENT ON COLUMN batch.workflow_node.related_job_code IS 'JOB 节点关联的作业编码。';


COMMENT ON COLUMN batch.workflow_node.window_code IS '节点允许运行的批量窗口编码。';


COMMENT ON COLUMN batch.workflow_node.retry_policy IS '节点失败重试策略。';


COMMENT ON COLUMN batch.workflow_node.retry_max_count IS '节点最大重试次数。';


COMMENT ON COLUMN batch.workflow_node.timeout_seconds IS '节点执行超时秒数。';


COMMENT ON COLUMN batch.workflow_node.node_params IS '节点参数 JSON；执行时解析为受控输入。';


COMMENT ON COLUMN batch.workflow_node.tenant_id IS 'V84(2026-05-03) 补充,取自 workflow_definition.tenant_id;落地 unique(tenant_id, ...) 多租隔离原则';


COMMENT ON COLUMN batch.workflow_node.cross_day_dependencies IS 'ADR-018 跨批量日上游依赖声明；JSONB 数组元素包含 alias、jobCode、bizDateOffset 或 bizDateRange、scope、consumeVersionStrategy 和可选 specificVersionNo；NULL 表示无跨日依赖。';


COMMENT ON COLUMN batch.workflow_node.cross_day_dependency_timeout_seconds IS 'ADR-018 依赖未齐的等待上限秒数; 0 表示永不超时; 超时后按 scope: REQUIRED→FAIL, OPTIONAL→continue';


COMMENT ON COLUMN batch.workflow_node.task_timeout_seconds IS 'ORCH-P4-2 task 级 startToClose timeout（秒）；NULL/0 = 无 timeout，超时平台主动 cancel';


CREATE SEQUENCE batch.workflow_node_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE batch.workflow_node_id_seq OWNED BY batch.workflow_node.id;


CREATE TABLE batch.workflow_terminal_notification_receipt (
    tenant_id character varying(64) NOT NULL,
    event_key character varying(256) NOT NULL,
    payload_json jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT workflow_terminal_notification_receipt_pkey PRIMARY KEY (tenant_id, event_key)
);

ALTER TABLE ONLY batch.workflow_terminal_notification_receipt FORCE ROW LEVEL SECURITY;


COMMENT ON TABLE batch.workflow_terminal_notification_receipt IS 'Workflow 终态通知消费回执；与收件人待投递记录同事务提交，重投不重新展开订阅。';


COMMENT ON COLUMN batch.workflow_terminal_notification_receipt.tenant_id IS '事件所属租户。';


COMMENT ON COLUMN batch.workflow_terminal_notification_receipt.event_key IS '稳定的租户/WorkflowRun/terminal 事件身份。';


COMMENT ON COLUMN batch.workflow_terminal_notification_receipt.payload_json IS '首次消费的不可变终态载荷。';


COMMENT ON COLUMN batch.workflow_terminal_notification_receipt.created_at IS '首次消费提交时间。';


CREATE TABLE quartz.qrtz_blob_triggers (
    sched_name character varying(120) NOT NULL,
    trigger_name character varying(200) NOT NULL,
    trigger_group character varying(200) NOT NULL,
    blob_data bytea,
    CONSTRAINT qrtz_blob_triggers_pkey PRIMARY KEY (sched_name, trigger_name, trigger_group)
);


CREATE TABLE quartz.qrtz_calendars (
    sched_name character varying(120) NOT NULL,
    calendar_name character varying(200) NOT NULL,
    calendar bytea NOT NULL,
    CONSTRAINT qrtz_calendars_pkey PRIMARY KEY (sched_name, calendar_name)
);


CREATE TABLE quartz.qrtz_cron_triggers (
    sched_name character varying(120) NOT NULL,
    trigger_name character varying(200) NOT NULL,
    trigger_group character varying(200) NOT NULL,
    cron_expression character varying(120) NOT NULL,
    time_zone_id character varying(80),
    CONSTRAINT qrtz_cron_triggers_pkey PRIMARY KEY (sched_name, trigger_name, trigger_group)
);


CREATE TABLE quartz.qrtz_fired_triggers (
    sched_name character varying(120) NOT NULL,
    entry_id character varying(95) NOT NULL,
    trigger_name character varying(200) NOT NULL,
    trigger_group character varying(200) NOT NULL,
    instance_name character varying(200) NOT NULL,
    fired_time bigint NOT NULL,
    sched_time bigint NOT NULL,
    priority integer NOT NULL,
    state character varying(16) NOT NULL,
    job_name character varying(200),
    job_group character varying(200),
    is_nonconcurrent boolean,
    requests_recovery boolean,
    CONSTRAINT qrtz_fired_triggers_pkey PRIMARY KEY (sched_name, entry_id)
);


CREATE TABLE quartz.qrtz_job_details (
    sched_name character varying(120) NOT NULL,
    job_name character varying(200) NOT NULL,
    job_group character varying(200) NOT NULL,
    description character varying(250),
    job_class_name character varying(250) NOT NULL,
    is_durable boolean NOT NULL,
    is_nonconcurrent boolean NOT NULL,
    is_update_data boolean NOT NULL,
    requests_recovery boolean NOT NULL,
    job_data bytea,
    CONSTRAINT qrtz_job_details_pkey PRIMARY KEY (sched_name, job_name, job_group)
);


CREATE TABLE quartz.qrtz_locks (
    sched_name character varying(120) NOT NULL,
    lock_name character varying(40) NOT NULL,
    CONSTRAINT qrtz_locks_pkey PRIMARY KEY (sched_name, lock_name)
);


CREATE TABLE quartz.qrtz_paused_trigger_grps (
    sched_name character varying(120) NOT NULL,
    trigger_group character varying(200) NOT NULL,
    CONSTRAINT qrtz_paused_trigger_grps_pkey PRIMARY KEY (sched_name, trigger_group)
);


CREATE TABLE quartz.qrtz_scheduler_state (
    sched_name character varying(120) NOT NULL,
    instance_name character varying(200) NOT NULL,
    last_checkin_time bigint NOT NULL,
    checkin_interval bigint NOT NULL,
    CONSTRAINT qrtz_scheduler_state_pkey PRIMARY KEY (sched_name, instance_name)
);


CREATE TABLE quartz.qrtz_simple_triggers (
    sched_name character varying(120) NOT NULL,
    trigger_name character varying(200) NOT NULL,
    trigger_group character varying(200) NOT NULL,
    repeat_count bigint NOT NULL,
    repeat_interval bigint NOT NULL,
    times_triggered bigint NOT NULL,
    CONSTRAINT qrtz_simple_triggers_pkey PRIMARY KEY (sched_name, trigger_name, trigger_group)
);


CREATE TABLE quartz.qrtz_simprop_triggers (
    sched_name character varying(120) NOT NULL,
    trigger_name character varying(200) NOT NULL,
    trigger_group character varying(200) NOT NULL,
    str_prop_1 character varying(512),
    str_prop_2 character varying(512),
    str_prop_3 character varying(512),
    int_prop_1 integer,
    int_prop_2 integer,
    long_prop_1 bigint,
    long_prop_2 bigint,
    dec_prop_1 numeric(13,4),
    dec_prop_2 numeric(13,4),
    bool_prop_1 boolean,
    bool_prop_2 boolean,
    CONSTRAINT qrtz_simprop_triggers_pkey PRIMARY KEY (sched_name, trigger_name, trigger_group)
);


CREATE TABLE quartz.qrtz_triggers (
    sched_name character varying(120) NOT NULL,
    trigger_name character varying(200) NOT NULL,
    trigger_group character varying(200) NOT NULL,
    job_name character varying(200) NOT NULL,
    job_group character varying(200) NOT NULL,
    description character varying(250),
    next_fire_time bigint,
    prev_fire_time bigint,
    priority integer,
    trigger_state character varying(16) NOT NULL,
    trigger_type character varying(8) NOT NULL,
    start_time bigint NOT NULL,
    end_time bigint,
    calendar_name character varying(200),
    misfire_instr smallint,
    job_data bytea,
    CONSTRAINT qrtz_triggers_pkey PRIMARY KEY (sched_name, trigger_name, trigger_group)
);


ALTER TABLE ONLY batch.alert_escalation_notification_outbox ALTER COLUMN id SET DEFAULT nextval('batch.alert_escalation_notification_outbox_id_seq'::regclass);


ALTER TABLE ONLY batch.alert_event ALTER COLUMN id SET DEFAULT nextval('batch.alert_event_id_seq'::regclass);


ALTER TABLE ONLY batch.alert_routing_config ALTER COLUMN id SET DEFAULT nextval('batch.alert_routing_config_id_seq'::regclass);


ALTER TABLE ONLY batch.api_key ALTER COLUMN id SET DEFAULT nextval('batch.api_key_id_seq'::regclass);


ALTER TABLE ONLY batch.approval_command ALTER COLUMN id SET DEFAULT nextval('batch.approval_command_id_seq'::regclass);


ALTER TABLE ONLY batch.archive_policy ALTER COLUMN id SET DEFAULT nextval('batch.archive_policy_id_seq'::regclass);


ALTER TABLE ONLY batch.asset_freshness_policy ALTER COLUMN id SET DEFAULT nextval('batch.asset_freshness_policy_id_seq'::regclass);


ALTER TABLE ONLY batch.asset_partition ALTER COLUMN id SET DEFAULT nextval('batch.asset_partition_id_seq'::regclass);


ALTER TABLE ONLY batch.atomic_task_config ALTER COLUMN id SET DEFAULT nextval('batch.atomic_task_config_id_seq'::regclass);


ALTER TABLE ONLY batch.batch_day_catchup_control_event ALTER COLUMN id SET DEFAULT nextval('batch.batch_day_catchup_control_event_id_seq'::regclass);


ALTER TABLE ONLY batch.batch_day_instance ALTER COLUMN id SET DEFAULT nextval('batch.batch_day_instance_id_seq'::regclass);


ALTER TABLE ONLY batch.batch_day_operation_audit ALTER COLUMN id SET DEFAULT nextval('batch.batch_day_operation_audit_id_seq'::regclass);


ALTER TABLE ONLY batch.batch_day_replay_entry ALTER COLUMN id SET DEFAULT nextval('batch.batch_day_replay_entry_id_seq'::regclass);


ALTER TABLE ONLY batch.batch_day_replay_session ALTER COLUMN id SET DEFAULT nextval('batch.batch_day_replay_session_id_seq'::regclass);


ALTER TABLE ONLY batch.batch_day_waiting_launch ALTER COLUMN id SET DEFAULT nextval('batch.batch_day_waiting_launch_id_seq'::regclass);


ALTER TABLE ONLY batch.batch_runtime_default_parameter ALTER COLUMN id SET DEFAULT nextval('batch.batch_runtime_default_parameter_id_seq'::regclass);


ALTER TABLE ONLY batch.batch_window ALTER COLUMN id SET DEFAULT nextval('batch.batch_window_id_seq'::regclass);


ALTER TABLE ONLY batch.biz_table_schema ALTER COLUMN id SET DEFAULT nextval('batch.biz_table_schema_id_seq'::regclass);


ALTER TABLE ONLY batch.business_calendar ALTER COLUMN id SET DEFAULT nextval('batch.business_calendar_id_seq'::regclass);


ALTER TABLE ONLY batch.calendar_dependency ALTER COLUMN id SET DEFAULT nextval('batch.calendar_dependency_id_seq'::regclass);


ALTER TABLE ONLY batch.calendar_dependency_wait ALTER COLUMN id SET DEFAULT nextval('batch.calendar_dependency_wait_id_seq'::regclass);


ALTER TABLE ONLY batch.calendar_group ALTER COLUMN id SET DEFAULT nextval('batch.calendar_group_id_seq'::regclass);


ALTER TABLE ONLY batch.calendar_holiday ALTER COLUMN id SET DEFAULT nextval('batch.calendar_holiday_id_seq'::regclass);


ALTER TABLE ONLY batch.compensation_command ALTER COLUMN id SET DEFAULT nextval('batch.compensation_command_id_seq'::regclass);


ALTER TABLE ONLY batch.config_approval ALTER COLUMN id SET DEFAULT nextval('batch.config_approval_id_seq'::regclass);


ALTER TABLE ONLY batch.config_cache_invalidation_outbox ALTER COLUMN id SET DEFAULT nextval('batch.config_cache_invalidation_outbox_id_seq'::regclass);


ALTER TABLE ONLY batch.config_change_log ALTER COLUMN id SET DEFAULT nextval('batch.config_change_log_id_seq'::regclass);


ALTER TABLE ONLY batch.config_release ALTER COLUMN id SET DEFAULT nextval('batch.config_release_id_seq'::regclass);


ALTER TABLE ONLY batch.config_sync_log ALTER COLUMN id SET DEFAULT nextval('batch.config_sync_log_id_seq'::regclass);


ALTER TABLE ONLY batch.console_ai_audit_log ALTER COLUMN id SET DEFAULT nextval('batch.console_ai_audit_log_id_seq'::regclass);


ALTER TABLE ONLY batch.console_operation_audit ALTER COLUMN id SET DEFAULT nextval('batch.console_operation_audit_id_seq'::regclass);


ALTER TABLE ONLY batch.console_push_approval_notification ALTER COLUMN id SET DEFAULT nextval('batch.console_push_approval_notification_id_seq'::regclass);


ALTER TABLE ONLY batch.console_push_job_delivery ALTER COLUMN id SET DEFAULT nextval('batch.console_push_job_delivery_id_seq'::regclass);


ALTER TABLE ONLY batch.console_push_subscription ALTER COLUMN id SET DEFAULT nextval('batch.console_push_subscription_id_seq'::regclass);


ALTER TABLE ONLY batch.console_user_account ALTER COLUMN id SET DEFAULT nextval('batch.console_user_account_id_seq'::regclass);


ALTER TABLE ONLY batch.custom_task_type_registry ALTER COLUMN id SET DEFAULT nextval('batch.custom_task_type_registry_id_seq'::regclass);


ALTER TABLE ONLY batch.data_asset ALTER COLUMN id SET DEFAULT nextval('batch.data_asset_id_seq'::regclass);


ALTER TABLE ONLY batch.data_quality_check ALTER COLUMN id SET DEFAULT nextval('batch.data_quality_check_id_seq'::regclass);


ALTER TABLE ONLY batch.data_quality_rule ALTER COLUMN id SET DEFAULT nextval('batch.data_quality_rule_id_seq'::regclass);


ALTER TABLE ONLY batch.dead_letter_task ALTER COLUMN id SET DEFAULT nextval('batch.dead_letter_task_id_seq'::regclass);


ALTER TABLE ONLY batch.disaster_day_override ALTER COLUMN id SET DEFAULT nextval('batch.disaster_day_override_id_seq'::regclass);


ALTER TABLE ONLY batch.event_delivery_log ALTER COLUMN id SET DEFAULT nextval('batch.event_delivery_log_id_seq'::regclass);


ALTER TABLE ONLY batch.event_outbox_retry ALTER COLUMN id SET DEFAULT nextval('batch.event_outbox_retry_id_seq'::regclass);


ALTER TABLE ONLY batch.expected_work_occurrence ALTER COLUMN id SET DEFAULT nextval('batch.expected_work_occurrence_id_seq'::regclass);


ALTER TABLE ONLY batch.external_event_delivery ALTER COLUMN id SET DEFAULT nextval('batch.external_event_delivery_id_seq'::regclass);


ALTER TABLE ONLY batch.external_event_expectation_definition ALTER COLUMN id SET DEFAULT nextval('batch.external_event_expectation_definition_id_seq'::regclass);


ALTER TABLE ONLY batch.file_audit_log ALTER COLUMN id SET DEFAULT nextval('batch.file_audit_log_id_seq'::regclass);


ALTER TABLE ONLY batch.file_channel_config ALTER COLUMN id SET DEFAULT nextval('batch.file_channel_config_id_seq'::regclass);


ALTER TABLE ONLY batch.file_channel_health ALTER COLUMN id SET DEFAULT nextval('batch.file_channel_health_id_seq'::regclass);


ALTER TABLE ONLY batch.file_dispatch_record ALTER COLUMN id SET DEFAULT nextval('batch.file_dispatch_record_id_seq'::regclass);


ALTER TABLE ONLY batch.file_error_record ALTER COLUMN id SET DEFAULT nextval('batch.file_error_record_id_seq'::regclass);


ALTER TABLE ONLY batch.file_group_expectation_definition ALTER COLUMN id SET DEFAULT nextval('batch.file_group_expectation_definition_id_seq'::regclass);


ALTER TABLE ONLY batch.file_record ALTER COLUMN id SET DEFAULT nextval('batch.file_record_id_seq'::regclass);


ALTER TABLE ONLY batch.file_template_config ALTER COLUMN id SET DEFAULT nextval('batch.file_template_config_id_seq'::regclass);


ALTER TABLE ONLY batch.forensic_export_log ALTER COLUMN id SET DEFAULT nextval('batch.forensic_export_log_id_seq'::regclass);


ALTER TABLE ONLY batch.idempotency_record ALTER COLUMN id SET DEFAULT nextval('batch.idempotency_record_id_seq'::regclass);


ALTER TABLE ONLY batch.job_definition ALTER COLUMN id SET DEFAULT nextval('batch.job_definition_id_seq'::regclass);


ALTER TABLE ONLY batch.job_dependency_waiting_launch ALTER COLUMN id SET DEFAULT nextval('batch.job_dependency_waiting_launch_id_seq'::regclass);


ALTER TABLE ONLY batch.job_execution_log ALTER COLUMN id SET DEFAULT nextval('batch.job_execution_log_id_seq'::regclass);


ALTER TABLE ONLY batch.job_instance ALTER COLUMN id SET DEFAULT nextval('batch.job_instance_id_seq'::regclass);


ALTER TABLE ONLY batch.job_monitoring_policy ALTER COLUMN id SET DEFAULT nextval('batch.job_monitoring_policy_id_seq'::regclass);


ALTER TABLE ONLY batch.job_partition ALTER COLUMN id SET DEFAULT nextval('batch.job_partition_id_seq'::regclass);


ALTER TABLE ONLY batch.job_step_instance ALTER COLUMN id SET DEFAULT nextval('batch.job_step_instance_id_seq'::regclass);


ALTER TABLE ONLY batch.job_task ALTER COLUMN id SET DEFAULT nextval('batch.job_task_id_seq'::regclass);


ALTER TABLE ONLY batch.notification_channel ALTER COLUMN id SET DEFAULT nextval('batch.notification_channel_id_seq'::regclass);


ALTER TABLE ONLY batch.notification_delivery_log ALTER COLUMN id SET DEFAULT nextval('batch.notification_delivery_log_id_seq'::regclass);


ALTER TABLE ONLY batch.outbox_event ALTER COLUMN id SET DEFAULT nextval('batch.outbox_event_id_seq'::regclass);


ALTER TABLE ONLY batch.pipeline_instance ALTER COLUMN id SET DEFAULT nextval('batch.pipeline_instance_id_seq'::regclass);


ALTER TABLE ONLY batch.pipeline_progress ALTER COLUMN id SET DEFAULT nextval('batch.pipeline_progress_id_seq'::regclass);


ALTER TABLE ONLY batch.pipeline_step_run ALTER COLUMN id SET DEFAULT nextval('batch.pipeline_step_run_id_seq'::regclass);


ALTER TABLE ONLY batch.quota_runtime_state ALTER COLUMN id SET DEFAULT nextval('batch.quota_runtime_state_id_seq'::regclass);


ALTER TABLE ONLY batch.readiness_wait ALTER COLUMN id SET DEFAULT nextval('batch.readiness_wait_id_seq'::regclass);


ALTER TABLE ONLY batch.replay_operation ALTER COLUMN id SET DEFAULT nextval('batch.replay_operation_id_seq'::regclass);


ALTER TABLE ONLY batch.resource_queue ALTER COLUMN id SET DEFAULT nextval('batch.resource_queue_id_seq'::regclass);


ALTER TABLE ONLY batch.resource_tag ALTER COLUMN id SET DEFAULT nextval('batch.resource_tag_id_seq'::regclass);


ALTER TABLE ONLY batch.result_version ALTER COLUMN id SET DEFAULT nextval('batch.result_version_id_seq'::regclass);


ALTER TABLE ONLY batch.retry_schedule ALTER COLUMN id SET DEFAULT nextval('batch.retry_schedule_id_seq'::regclass);


ALTER TABLE ONLY batch.scheduled_occurrence ALTER COLUMN id SET DEFAULT nextval('batch.scheduled_occurrence_id_seq'::regclass);


ALTER TABLE ONLY batch.scheduled_occurrence_catch_up_audit ALTER COLUMN id SET DEFAULT nextval('batch.scheduled_occurrence_catch_up_audit_id_seq'::regclass);


ALTER TABLE ONLY batch.scheduled_occurrence_fire ALTER COLUMN id SET DEFAULT nextval('batch.scheduled_occurrence_fire_id_seq'::regclass);


ALTER TABLE ONLY batch.scheduled_occurrence_supersession_history ALTER COLUMN id SET DEFAULT nextval('batch.scheduled_occurrence_supersession_history_id_seq'::regclass);


ALTER TABLE ONLY batch.secret_version ALTER COLUMN id SET DEFAULT nextval('batch.secret_version_id_seq'::regclass);


ALTER TABLE ONLY batch.step_registry ALTER COLUMN id SET DEFAULT nextval('batch.step_registry_id_seq'::regclass);


ALTER TABLE ONLY batch.subscription_rule ALTER COLUMN id SET DEFAULT nextval('batch.subscription_rule_id_seq'::regclass);


ALTER TABLE ONLY batch.system_parameter ALTER COLUMN id SET DEFAULT nextval('batch.system_parameter_id_seq'::regclass);


ALTER TABLE ONLY batch.tenant ALTER COLUMN id SET DEFAULT nextval('batch.tenant_id_seq'::regclass);


ALTER TABLE ONLY batch.tenant_quota_policy ALTER COLUMN id SET DEFAULT nextval('batch.tenant_quota_policy_id_seq'::regclass);


ALTER TABLE ONLY batch.tenant_scheduler_snapshot ALTER COLUMN id SET DEFAULT nextval('batch.tenant_scheduler_snapshot_id_seq'::regclass);


ALTER TABLE ONLY batch.trigger_launch_recovery ALTER COLUMN id SET DEFAULT nextval('batch.trigger_launch_recovery_id_seq'::regclass);


ALTER TABLE ONLY batch.trigger_misfire_pending ALTER COLUMN id SET DEFAULT nextval('batch.trigger_misfire_pending_id_seq'::regclass);


ALTER TABLE ONLY batch.trigger_outbox_event ALTER COLUMN id SET DEFAULT nextval('batch.trigger_outbox_event_id_seq'::regclass);


ALTER TABLE ONLY batch.trigger_request ALTER COLUMN id SET DEFAULT nextval('batch.trigger_request_id_seq'::regclass);


ALTER TABLE ONLY batch.webhook_delivery_log ALTER COLUMN id SET DEFAULT nextval('batch.webhook_delivery_log_id_seq'::regclass);


ALTER TABLE ONLY batch.webhook_subscription ALTER COLUMN id SET DEFAULT nextval('batch.webhook_subscription_id_seq'::regclass);


ALTER TABLE ONLY batch.worker_registry ALTER COLUMN id SET DEFAULT nextval('batch.worker_registry_id_seq'::regclass);


ALTER TABLE ONLY batch.worker_report_outbox ALTER COLUMN id SET DEFAULT nextval('batch.worker_report_outbox_id_seq'::regclass);


ALTER TABLE ONLY batch.workflow_definition ALTER COLUMN id SET DEFAULT nextval('batch.workflow_definition_id_seq'::regclass);


ALTER TABLE ONLY batch.workflow_definition_version ALTER COLUMN id SET DEFAULT nextval('batch.workflow_definition_version_id_seq'::regclass);


ALTER TABLE ONLY batch.workflow_edge ALTER COLUMN id SET DEFAULT nextval('batch.workflow_edge_id_seq'::regclass);


ALTER TABLE ONLY batch.workflow_launch_wait ALTER COLUMN id SET DEFAULT nextval('batch.workflow_launch_wait_id_seq'::regclass);


ALTER TABLE ONLY batch.workflow_node ALTER COLUMN id SET DEFAULT nextval('batch.workflow_node_id_seq'::regclass);


ALTER TABLE ONLY batch.workflow_node_run ALTER COLUMN id SET DEFAULT nextval('batch.workflow_node_run_id_seq'::regclass);


ALTER TABLE ONLY batch.workflow_run ALTER COLUMN id SET DEFAULT nextval('batch.workflow_run_id_seq'::regclass);



































































-- 写入空库必须具备的内置参考数据，再恢复相应序列的初始位置。
INSERT INTO batch.archive_policy (id, tenant_id, target_table, retention_days, archive_enabled, cleanup_enabled, batch_size, description, created_by, updated_by, created_at, updated_at) VALUES (1, 'default-tenant', 'console_operation_audit', 180, false, false, 1000, '通用控制台操作审计(@AuditAction 落库)。enable cleanup_enabled 才会自动删除超期数据', 'system', 'system', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.archive_policy (id, tenant_id, target_table, retention_days, archive_enabled, cleanup_enabled, batch_size, description, created_by, updated_by, created_at, updated_at) VALUES (2, 'system', 'console_operation_audit', 180, false, false, 1000, '通用控制台操作审计(@AuditAction 落库)。enable cleanup_enabled 才会自动删除超期数据', 'system', 'system', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.archive_policy (id, tenant_id, target_table, retention_days, archive_enabled, cleanup_enabled, batch_size, description, created_by, updated_by, created_at, updated_at) VALUES (3, 'default', 'console_operation_audit', 180, false, false, 1000, '通用控制台操作审计(@AuditAction 落库)。enable cleanup_enabled 才会自动删除超期数据', 'system', 'system', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.archive_policy (id, tenant_id, target_table, retention_days, archive_enabled, cleanup_enabled, batch_size, description, created_by, updated_by, created_at, updated_at) VALUES (4, 'default-tenant', 'trigger_outbox_event', 7, true, true, 1000, 'ADR-010 trigger 异步事件,PUBLISHED 后保留 7 天供回查,GIVE_UP 由 cleanup 脚本独立控制', 'system', 'system', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.archive_policy (id, tenant_id, target_table, retention_days, archive_enabled, cleanup_enabled, batch_size, description, created_by, updated_by, created_at, updated_at) VALUES (5, 'system', 'trigger_outbox_event', 7, true, true, 1000, 'ADR-010 trigger 异步事件,PUBLISHED 后保留 7 天供回查,GIVE_UP 由 cleanup 脚本独立控制', 'system', 'system', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.archive_policy (id, tenant_id, target_table, retention_days, archive_enabled, cleanup_enabled, batch_size, description, created_by, updated_by, created_at, updated_at) VALUES (6, 'default', 'trigger_outbox_event', 7, true, true, 1000, 'ADR-010 trigger 异步事件,PUBLISHED 后保留 7 天供回查,GIVE_UP 由 cleanup 脚本独立控制', 'system', 'system', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.archive_policy (id, tenant_id, target_table, retention_days, archive_enabled, cleanup_enabled, batch_size, description, created_by, updated_by, created_at, updated_at) VALUES (7, 'default-tenant', 'dead_letter_task', 90, true, false, 500, '死信任务,GIVE_UP 行 90 天后允许归档;cleanup_enabled 由运维确认 90 天为合规边界后再开', 'system', 'system', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.archive_policy (id, tenant_id, target_table, retention_days, archive_enabled, cleanup_enabled, batch_size, description, created_by, updated_by, created_at, updated_at) VALUES (8, 'system', 'dead_letter_task', 90, true, false, 500, '死信任务,GIVE_UP 行 90 天后允许归档;cleanup_enabled 由运维确认 90 天为合规边界后再开', 'system', 'system', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.archive_policy (id, tenant_id, target_table, retention_days, archive_enabled, cleanup_enabled, batch_size, description, created_by, updated_by, created_at, updated_at) VALUES (9, 'default', 'dead_letter_task', 90, true, false, 500, '死信任务,GIVE_UP 行 90 天后允许归档;cleanup_enabled 由运维确认 90 天为合规边界后再开', 'system', 'system', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.archive_policy (id, tenant_id, target_table, retention_days, archive_enabled, cleanup_enabled, batch_size, description, created_by, updated_by, created_at, updated_at) VALUES (10, 'default-tenant', 'job_execution_log', 30, true, true, 2000, '作业执行日志(DEBUG/INFO/WARN/ERROR + SYSTEM/BUSINESS/RETRY/ALARM/AUDIT),30 天后归档+清理', 'system', 'system', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.archive_policy (id, tenant_id, target_table, retention_days, archive_enabled, cleanup_enabled, batch_size, description, created_by, updated_by, created_at, updated_at) VALUES (11, 'system', 'job_execution_log', 30, true, true, 2000, '作业执行日志(DEBUG/INFO/WARN/ERROR + SYSTEM/BUSINESS/RETRY/ALARM/AUDIT),30 天后归档+清理', 'system', 'system', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.archive_policy (id, tenant_id, target_table, retention_days, archive_enabled, cleanup_enabled, batch_size, description, created_by, updated_by, created_at, updated_at) VALUES (12, 'default', 'job_execution_log', 30, true, true, 2000, '作业执行日志(DEBUG/INFO/WARN/ERROR + SYSTEM/BUSINESS/RETRY/ALARM/AUDIT),30 天后归档+清理', 'system', 'system', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);















INSERT INTO batch.business_calendar (id, tenant_id, calendar_code, calendar_name, timezone, holiday_roll_rule, catch_up_policy, catch_up_max_days, enabled, created_at, updated_at, cutoff_time, late_arrival_tolerance_min, sla_offset_min, day_rollover_policy, dst_gap_policy, dst_overlap_policy, group_code, cutoff_schedule, description) VALUES (1, 'default', 'default_calendar', 'Default Calendar', 'Asia/Shanghai', 'NEXT_WORKDAY', 'AUTO', 3, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '06:00:00', 60, 0, 'ALLOW_OVERLAP', 'RUN_AT_NEXT_VALID_TIME', 'RUN_ONCE_EARLIER_OFFSET', NULL, NULL, NULL);



























INSERT INTO batch.batch_runtime_default_parameter (id, module, parameter_key, default_value, value_type, unit, yaml_path, env_var, description, created_at, updated_at) VALUES (1, 'ORCHESTRATOR', 'outbox.batch_size', '100', 'INTEGER', 'events', 'batch.outbox.batch-size', 'BATCH_OUTBOX_BATCH_SIZE', 'Outbox poll batch size', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.batch_runtime_default_parameter (id, module, parameter_key, default_value, value_type, unit, yaml_path, env_var, description, created_at, updated_at) VALUES (2, 'ORCHESTRATOR', 'outbox.poll_interval_millis', '5000', 'MILLIS', NULL, 'batch.outbox.poll-interval-millis', 'BATCH_OUTBOX_POLL_INTERVAL_MILLIS', 'Outbox poller interval', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.batch_runtime_default_parameter (id, module, parameter_key, default_value, value_type, unit, yaml_path, env_var, description, created_at, updated_at) VALUES (3, 'ORCHESTRATOR', 'outbox.retry_delay_seconds', '60', 'SECONDS', NULL, 'batch.outbox.retry-delay-seconds', 'BATCH_OUTBOX_RETRY_DELAY_SECONDS', 'Delay before retrying failed outbox publish', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.batch_runtime_default_parameter (id, module, parameter_key, default_value, value_type, unit, yaml_path, env_var, description, created_at, updated_at) VALUES (4, 'ORCHESTRATOR', 'outbox.max_retry_attempts', '5', 'INTEGER', NULL, 'batch.outbox.max-retry-attempts', 'BATCH_OUTBOX_MAX_RETRY_ATTEMPTS', 'Max publish attempts per outbox event', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.batch_runtime_default_parameter (id, module, parameter_key, default_value, value_type, unit, yaml_path, env_var, description, created_at, updated_at) VALUES (5, 'ORCHESTRATOR', 'worker.drain.default_timeout_seconds', '600', 'SECONDS', NULL, 'batch.worker.drain.default-timeout-seconds', 'BATCH_WORKER_DRAIN_TIMEOUT_SECONDS', 'Default drain window before task takeover', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.batch_runtime_default_parameter (id, module, parameter_key, default_value, value_type, unit, yaml_path, env_var, description, created_at, updated_at) VALUES (6, 'ORCHESTRATOR', 'worker.drain.check_interval_millis', '15000', 'MILLIS', NULL, 'batch.worker.drain.check-interval-millis', 'BATCH_WORKER_DRAIN_CHECK_INTERVAL_MILLIS', 'Scheduler scan for drain deadline expiry', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.batch_runtime_default_parameter (id, module, parameter_key, default_value, value_type, unit, yaml_path, env_var, description, created_at, updated_at) VALUES (7, 'ORCHESTRATOR', 'worker.drain.enabled', 'true', 'BOOLEAN', NULL, 'batch.worker.drain.enabled', 'BATCH_WORKER_DRAIN_ENABLED', 'Enable drain timeout scheduler', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.batch_runtime_default_parameter (id, module, parameter_key, default_value, value_type, unit, yaml_path, env_var, description, created_at, updated_at) VALUES (8, 'ORCHESTRATOR', 'partition_lease.expire_seconds', '60', 'SECONDS', NULL, 'batch.partition-lease.expire-seconds', 'BATCH_PARTITION_LEASE_EXPIRE_SECONDS', 'Partition lease TTL', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.batch_runtime_default_parameter (id, module, parameter_key, default_value, value_type, unit, yaml_path, env_var, description, created_at, updated_at) VALUES (9, 'ORCHESTRATOR', 'partition_lease.reclaim_interval_millis', '15000', 'MILLIS', NULL, 'batch.partition-lease.reclaim-interval-millis', 'BATCH_PARTITION_LEASE_RECLAIM_INTERVAL_MILLIS', 'Expired lease reclaim cadence', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.batch_runtime_default_parameter (id, module, parameter_key, default_value, value_type, unit, yaml_path, env_var, description, created_at, updated_at) VALUES (10, 'ORCHESTRATOR', 'retry.batch_size', '100', 'INTEGER', 'tasks', 'batch.retry.batch-size', 'BATCH_RETRY_BATCH_SIZE', 'Retry scheduler batch size', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.batch_runtime_default_parameter (id, module, parameter_key, default_value, value_type, unit, yaml_path, env_var, description, created_at, updated_at) VALUES (11, 'ORCHESTRATOR', 'retry.poll_interval_millis', '10000', 'MILLIS', NULL, 'batch.retry.poll-interval-millis', 'BATCH_RETRY_POLL_INTERVAL_MILLIS', 'Retry scheduler poll interval', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.batch_runtime_default_parameter (id, module, parameter_key, default_value, value_type, unit, yaml_path, env_var, description, created_at, updated_at) VALUES (12, 'ORCHESTRATOR', 'retry.fixed_delay_seconds', '60', 'SECONDS', NULL, 'batch.retry.fixed-delay-seconds', 'BATCH_RETRY_FIXED_DELAY_SECONDS', 'Base delay for retry scheduling', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.batch_runtime_default_parameter (id, module, parameter_key, default_value, value_type, unit, yaml_path, env_var, description, created_at, updated_at) VALUES (13, 'ORCHESTRATOR', 'retry.exponential_multiplier', '2', 'INTEGER', NULL, 'batch.retry.exponential-multiplier', 'BATCH_RETRY_EXPONENTIAL_MULTIPLIER', 'Backoff multiplier', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.batch_runtime_default_parameter (id, module, parameter_key, default_value, value_type, unit, yaml_path, env_var, description, created_at, updated_at) VALUES (14, 'ORCHESTRATOR', 'retry.max_delay_seconds', '3600', 'SECONDS', NULL, 'batch.retry.max-delay-seconds', 'BATCH_RETRY_MAX_DELAY_SECONDS', 'Backoff cap', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.batch_runtime_default_parameter (id, module, parameter_key, default_value, value_type, unit, yaml_path, env_var, description, created_at, updated_at) VALUES (15, 'ORCHESTRATOR', 'retry.default_max_retry_count', '3', 'INTEGER', NULL, 'batch.retry.default-max-retry-count', 'BATCH_RETRY_DEFAULT_MAX_RETRY_COUNT', 'Default max retries per task', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.batch_runtime_default_parameter (id, module, parameter_key, default_value, value_type, unit, yaml_path, env_var, description, created_at, updated_at) VALUES (16, 'ORCHESTRATOR', 'resource_scheduler.waiting_dispatch_batch_size', '100', 'INTEGER', NULL, 'batch.resource-scheduler.waiting-dispatch-batch-size', 'BATCH_RESOURCE_SCHEDULER_WAITING_DISPATCH_BATCH_SIZE', 'Waiting-queue release batch', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.batch_runtime_default_parameter (id, module, parameter_key, default_value, value_type, unit, yaml_path, env_var, description, created_at, updated_at) VALUES (17, 'ORCHESTRATOR', 'resource_scheduler.waiting_dispatch_interval_millis', '10000', 'MILLIS', NULL, 'batch.resource-scheduler.waiting-dispatch-interval-millis', 'BATCH_RESOURCE_SCHEDULER_WAITING_DISPATCH_INTERVAL_MILLIS', 'Waiting-queue release interval', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.batch_runtime_default_parameter (id, module, parameter_key, default_value, value_type, unit, yaml_path, env_var, description, created_at, updated_at) VALUES (18, 'ORCHESTRATOR', 'resource_scheduler.quota_reset_sliding_window_hours', '24', 'INTEGER', 'hours', 'batch.resource-scheduler.quota-reset-sliding-window-hours', 'BATCH_RESOURCE_SCHEDULER_QUOTA_RESET_SLIDING_WINDOW_HOURS', 'Burst quota sliding-window duration', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.batch_runtime_default_parameter (id, module, parameter_key, default_value, value_type, unit, yaml_path, env_var, description, created_at, updated_at) VALUES (19, 'ORCHESTRATOR', 'resource_scheduler.quota_reset_scan_interval_millis', '60000', 'MILLIS', NULL, 'batch.resource-scheduler.quota-reset-scan-interval-millis', 'BATCH_RESOURCE_SCHEDULER_QUOTA_RESET_SCAN_INTERVAL_MILLIS', 'Quota runtime reset scan interval', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.batch_runtime_default_parameter (id, module, parameter_key, default_value, value_type, unit, yaml_path, env_var, description, created_at, updated_at) VALUES (20, 'ORCHESTRATOR', 'scheduler.snapshot_persist_ms', '120000', 'MILLIS', NULL, 'batch.scheduler.snapshot-persist-ms', 'BATCH_SCHEDULER_SNAPSHOT_PERSIST_MS', 'Tenant scheduler snapshot write interval', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.batch_runtime_default_parameter (id, module, parameter_key, default_value, value_type, unit, yaml_path, env_var, description, created_at, updated_at) VALUES (21, 'ORCHESTRATOR', 'sla.poll_interval_millis', '30000', 'MILLIS', NULL, 'batch.sla.poll-interval-millis', 'BATCH_SLA_POLL_INTERVAL_MILLIS', 'SLA checker interval', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.batch_runtime_default_parameter (id, module, parameter_key, default_value, value_type, unit, yaml_path, env_var, description, created_at, updated_at) VALUES (22, 'ORCHESTRATOR', 'sla.batch_size', '200', 'INTEGER', NULL, 'batch.sla.batch-size', 'BATCH_SLA_BATCH_SIZE', 'SLA evaluation batch', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.batch_runtime_default_parameter (id, module, parameter_key, default_value, value_type, unit, yaml_path, env_var, description, created_at, updated_at) VALUES (23, 'ORCHESTRATOR', 'file_governance.latency.poll_interval_millis', '30000', 'MILLIS', NULL, 'batch.file-governance.latency.poll-interval-millis', 'BATCH_FILE_GOVERNANCE_LATENCY_POLL_INTERVAL_MILLIS', 'File latency governance poll', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.batch_runtime_default_parameter (id, module, parameter_key, default_value, value_type, unit, yaml_path, env_var, description, created_at, updated_at) VALUES (24, 'ORCHESTRATOR', 'file_governance.latency.arrival_delay_threshold_seconds', '600', 'SECONDS', NULL, 'batch.file-governance.latency.arrival-delay-threshold-seconds', 'BATCH_FILE_GOVERNANCE_ARRIVAL_DELAY_THRESHOLD_SECONDS', 'Arrival SLA threshold', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.batch_runtime_default_parameter (id, module, parameter_key, default_value, value_type, unit, yaml_path, env_var, description, created_at, updated_at) VALUES (25, 'ORCHESTRATOR', 'file_governance.latency.processing_delay_threshold_seconds', '900', 'SECONDS', NULL, 'batch.file-governance.latency.processing-delay-threshold-seconds', 'BATCH_FILE_GOVERNANCE_PROCESSING_DELAY_THRESHOLD_SECONDS', 'Processing SLA threshold', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.batch_runtime_default_parameter (id, module, parameter_key, default_value, value_type, unit, yaml_path, env_var, description, created_at, updated_at) VALUES (26, 'ORCHESTRATOR', 'file_governance.archive.cleanup_interval_millis', '60000', 'MILLIS', NULL, 'batch.file-governance.archive.cleanup-interval-millis', 'BATCH_FILE_GOVERNANCE_ARCHIVE_CLEANUP_INTERVAL_MILLIS', 'Archive cleanup scheduler', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.batch_runtime_default_parameter (id, module, parameter_key, default_value, value_type, unit, yaml_path, env_var, description, created_at, updated_at) VALUES (27, 'ORCHESTRATOR', 'file_governance.archive.cleanup_batch_size', '100', 'INTEGER', NULL, 'batch.file-governance.archive.cleanup-batch-size', 'BATCH_FILE_GOVERNANCE_ARCHIVE_CLEANUP_BATCH_SIZE', 'Archive cleanup batch', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.batch_runtime_default_parameter (id, module, parameter_key, default_value, value_type, unit, yaml_path, env_var, description, created_at, updated_at) VALUES (28, 'ORCHESTRATOR', 'file_governance.archive.retention_days', '7', 'INTEGER', 'days', 'batch.file-governance.archive.retention-days', 'BATCH_FILE_GOVERNANCE_ARCHIVE_RETENTION_DAYS', 'Soft retention before purge policy applies', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.batch_runtime_default_parameter (id, module, parameter_key, default_value, value_type, unit, yaml_path, env_var, description, created_at, updated_at) VALUES (29, 'ORCHESTRATOR', 'file_governance.reconcile.poll_interval_millis', '60000', 'MILLIS', NULL, 'batch.file-governance.reconcile.poll-interval-millis', 'BATCH_FILE_GOVERNANCE_RECONCILE_POLL_INTERVAL_MILLIS', 'Object vs record reconcile poll', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.batch_runtime_default_parameter (id, module, parameter_key, default_value, value_type, unit, yaml_path, env_var, description, created_at, updated_at) VALUES (30, 'ORCHESTRATOR', 'file_governance.reconcile.batch_size', '200', 'INTEGER', NULL, 'batch.file-governance.reconcile.batch-size', 'BATCH_FILE_GOVERNANCE_RECONCILE_BATCH_SIZE', 'Reconcile batch size', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.batch_runtime_default_parameter (id, module, parameter_key, default_value, value_type, unit, yaml_path, env_var, description, created_at, updated_at) VALUES (31, 'WORKER_IMPORT', 'file_processing.streaming_enabled', 'true', 'BOOLEAN', NULL, 'batch.worker.import.file-processing.streaming-enabled', 'BATCH_WORKER_IMPORT_STREAMING_ENABLED', 'Enable NDJSON/streaming import path', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.batch_runtime_default_parameter (id, module, parameter_key, default_value, value_type, unit, yaml_path, env_var, description, created_at, updated_at) VALUES (32, 'WORKER_IMPORT', 'file_processing.page_size', '1000', 'INTEGER', 'rows', 'batch.worker.import.file-processing.page-size', 'BATCH_WORKER_IMPORT_PAGE_SIZE', 'Parse/validate page size', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.batch_runtime_default_parameter (id, module, parameter_key, default_value, value_type, unit, yaml_path, env_var, description, created_at, updated_at) VALUES (33, 'WORKER_IMPORT', 'file_processing.fetch_size', '1000', 'INTEGER', 'rows', 'batch.worker.import.file-processing.fetch-size', 'BATCH_WORKER_IMPORT_FETCH_SIZE', 'JDBC fetch size hints', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.batch_runtime_default_parameter (id, module, parameter_key, default_value, value_type, unit, yaml_path, env_var, description, created_at, updated_at) VALUES (35, 'WORKER_EXPORT', 'file_processing.streaming_enabled', 'true', 'BOOLEAN', NULL, 'batch.worker.export.file-processing.streaming-enabled', 'BATCH_WORKER_EXPORT_STREAMING_ENABLED', 'Streaming export writes', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.batch_runtime_default_parameter (id, module, parameter_key, default_value, value_type, unit, yaml_path, env_var, description, created_at, updated_at) VALUES (36, 'WORKER_EXPORT', 'file_processing.page_size', '1000', 'INTEGER', 'rows', 'batch.worker.export.file-processing.page-size', 'BATCH_WORKER_EXPORT_PAGE_SIZE', 'Detail pagination page size', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.batch_runtime_default_parameter (id, module, parameter_key, default_value, value_type, unit, yaml_path, env_var, description, created_at, updated_at) VALUES (37, 'WORKER_EXPORT', 'file_processing.fetch_size', '1000', 'INTEGER', 'rows', 'batch.worker.export.file-processing.fetch-size', 'BATCH_WORKER_EXPORT_FETCH_SIZE', 'Reserved / JDBC fetch alignment', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.batch_runtime_default_parameter (id, module, parameter_key, default_value, value_type, unit, yaml_path, env_var, description, created_at, updated_at) VALUES (38, 'WORKER_EXPORT', 'file_processing.chunk_size', '500', 'INTEGER', 'rows', 'batch.worker.export.file-processing.chunk-size', 'BATCH_WORKER_EXPORT_CHUNK_SIZE', 'Flush chunk for delimited/json writers', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.batch_runtime_default_parameter (id, module, parameter_key, default_value, value_type, unit, yaml_path, env_var, description, created_at, updated_at) VALUES (39, 'WORKER_DISPATCH', 'circuit_breaker.failure_threshold', '5', 'INTEGER', 'failures', 'batch.worker.dispatch.circuit-breaker.failure-threshold', NULL, 'Failures before channel short-circuit', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.batch_runtime_default_parameter (id, module, parameter_key, default_value, value_type, unit, yaml_path, env_var, description, created_at, updated_at) VALUES (40, 'WORKER_DISPATCH', 'circuit_breaker.cooldown_millis', '60000', 'MILLIS', NULL, 'batch.worker.dispatch.circuit-breaker.cooldown-millis', 'BATCH_DISPATCH_CB_COOLDOWN_MS', 'Cooldown while circuit open', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.batch_runtime_default_parameter (id, module, parameter_key, default_value, value_type, unit, yaml_path, env_var, description, created_at, updated_at) VALUES (41, 'WORKER_DISPATCH', 'receipt_poll.interval_millis', '60000', 'MILLIS', NULL, 'batch.worker.dispatch.receipt-poll.interval-millis', 'BATCH_DISPATCH_RECEIPT_POLL_MS', 'Async receipt poll interval', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.batch_runtime_default_parameter (id, module, parameter_key, default_value, value_type, unit, yaml_path, env_var, description, created_at, updated_at) VALUES (42, 'WORKER_DISPATCH', 'receipt_poll.batch_size', '50', 'INTEGER', 'records', 'batch.worker.dispatch.receipt-poll.batch-size', 'BATCH_DISPATCH_RECEIPT_POLL_BATCH', 'Receipt poll batch', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.batch_runtime_default_parameter (id, module, parameter_key, default_value, value_type, unit, yaml_path, env_var, description, created_at, updated_at) VALUES (43, 'SCHEMA', 'file_template_config.chunk_size', '500', 'INTEGER', 'rows', 'batch.file_template_config.chunk_size (column default)', NULL, 'DB default when template row has no override; CHECK chunk_size > 0', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.batch_runtime_default_parameter (id, module, parameter_key, default_value, value_type, unit, yaml_path, env_var, description, created_at, updated_at) VALUES (44, 'WORKER_IMPORT', 'file_processing.max_chunk_size', '10000', 'INTEGER', 'rows', 'batch.worker.import.file-processing.max-chunk-size', 'BATCH_WORKER_IMPORT_MAX_CHUNK_SIZE', 'Upper bound for template chunk_size; protects JVM heap on COPY / wide rows', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO batch.batch_runtime_default_parameter (id, module, parameter_key, default_value, value_type, unit, yaml_path, env_var, description, created_at, updated_at) VALUES (34, 'WORKER_IMPORT', 'file_processing.chunk_size', '2000', 'INTEGER', 'rows', 'batch.worker.import.file-processing.chunk-size', 'BATCH_WORKER_IMPORT_CHUNK_SIZE', 'Load batch chunk; overridden by file_template_config.chunk_size up to max_chunk_size', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO batch.batch_window (id, tenant_id, window_code, window_name, timezone, start_time, end_time, end_strategy, out_of_window_action, allow_cross_day, enabled, description, created_at, updated_at) VALUES (1, 'default', 'always_open', 'Always Open', 'Asia/Shanghai', '00:00:00', '23:59:59', 'FINISH_RUNNING', 'WAIT', true, true, 'Full-day window template', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);











































INSERT INTO batch.console_user_account (id, tenant_id, username, display_name, password_hash, authorities_csv, enabled, created_at, updated_at, must_change_password) VALUES (1, 'default-tenant', 'tenant-user', 'Tenant User', '$argon2id$v=19$m=16384,t=2,p=1$k18enAVVcHofGDMPXPxj5A$5TityFxKIX2z6bkuDXRHqmwuPcfr+G9MEA36Kr6fC4s', 'ROLE_TENANT_USER', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, false);
INSERT INTO batch.console_user_account (id, tenant_id, username, display_name, password_hash, authorities_csv, enabled, created_at, updated_at, must_change_password) VALUES (2, 'system', 'admin', 'Console Admin', '$argon2id$v=19$m=16384,t=2,p=1$k18enAVVcHofGDMPXPxj5A$5TityFxKIX2z6bkuDXRHqmwuPcfr+G9MEA36Kr6fC4s', 'ROLE_ADMIN', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, true);
INSERT INTO batch.console_user_account (id, tenant_id, username, display_name, password_hash, authorities_csv, enabled, created_at, updated_at, must_change_password) VALUES (3, 'system', 'auditor', 'Console Auditor', '$argon2id$v=19$m=16384,t=2,p=1$k18enAVVcHofGDMPXPxj5A$5TityFxKIX2z6bkuDXRHqmwuPcfr+G9MEA36Kr6fC4s', 'ROLE_AUDITOR', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, true);
INSERT INTO batch.console_user_account (id, tenant_id, username, display_name, password_hash, authorities_csv, enabled, created_at, updated_at, must_change_password) VALUES (4, 'system', 'config-admin', 'Console Config Admin', '$argon2id$v=19$m=16384,t=2,p=1$k18enAVVcHofGDMPXPxj5A$5TityFxKIX2z6bkuDXRHqmwuPcfr+G9MEA36Kr6fC4s', 'ROLE_ADMIN', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, true);



INSERT INTO batch.console_maintenance_state (id, enabled, read_only, message, eta_at, affected_services, version, updated_by, updated_at, starts_at) VALUES (1, false, false, NULL, NULL, '[]', 0, NULL, CURRENT_TIMESTAMP, NULL);























































INSERT INTO batch.file_template_config (id, tenant_id, template_code, template_name, template_type, biz_type, file_format_type, charset, target_charset, with_bom, line_separator, delimiter, quote_char, escape_char, record_length, header_rows, footer_rows, header_template, trailer_template, checksum_type, compress_type, encrypt_type, naming_rule, field_mappings, validation_rule_set, default_query_code, default_query_sql, query_param_schema, streaming_enabled, page_size, fetch_size, chunk_size, enabled, version, description, created_by, updated_by, created_at, updated_at, preprocess_pipeline, preview_masking_enabled, error_line_masking_enabled, log_masking_enabled, content_encryption_enabled, encryption_key_ref, download_requires_approval, masking_rule_set, export_data_ref, load_target_ref, is_deleted) VALUES (1, 'default', 'tpl-import-csv', 'Generic CSV Import Template', 'IMPORT', 'GENERIC', 'DELIMITED', 'UTF-8', 'UTF-8', false, '
', ',', '"', '\\', 0, 1, 0, NULL, NULL, 'SHA-256', 'NONE', 'NONE', '$${bizType}-import-$${bizDate}-$${version}', '{}', '{}', NULL, NULL, '{}', true, 1000, 1000, 500, true, 1, 'Generic CSV import template — fill field_mappings / validation_rule_set / load_target_ref before use', 'system', 'system', '2026-11-22 04:46:47.175002+00', '2026-11-22 04:46:47.175002+00', NULL, false, false, false, false, NULL, false, NULL, NULL, 'jdbc_mapped', false);
INSERT INTO batch.file_template_config (id, tenant_id, template_code, template_name, template_type, biz_type, file_format_type, charset, target_charset, with_bom, line_separator, delimiter, quote_char, escape_char, record_length, header_rows, footer_rows, header_template, trailer_template, checksum_type, compress_type, encrypt_type, naming_rule, field_mappings, validation_rule_set, default_query_code, default_query_sql, query_param_schema, streaming_enabled, page_size, fetch_size, chunk_size, enabled, version, description, created_by, updated_by, created_at, updated_at, preprocess_pipeline, preview_masking_enabled, error_line_masking_enabled, log_masking_enabled, content_encryption_enabled, encryption_key_ref, download_requires_approval, masking_rule_set, export_data_ref, load_target_ref, is_deleted) VALUES (2, 'default', 'tpl-export-csv', 'Generic CSV Export Template', 'EXPORT', 'GENERIC', 'DELIMITED', 'UTF-8', 'UTF-8', false, '
', ',', '"', '\\', 0, 1, 0, NULL, NULL, 'SHA-256', 'NONE', 'NONE', '$${bizType}-export-$${bizDate}-$${version}', '{}', '{}', NULL, NULL, '{}', true, 1000, 1000, 500, true, 1, 'Generic CSV export template — fill field_mappings / default_query_sql / export_data_ref before use', 'system', 'system', '2026-11-22 04:46:47.175002+00', '2026-11-22 04:46:47.175002+00', NULL, false, false, false, false, NULL, false, NULL, 'sql_template_export', NULL, false);





















































INSERT INTO batch.resource_queue (id, tenant_id, queue_code, queue_name, queue_type, max_running_jobs, max_running_partitions, max_qps, worker_group, resource_tag, priority_policy, fair_share_weight, enabled, description, created_at, updated_at, fair_share_group, burst_limit, quota_reset_policy, group_shared_max_running_jobs) VALUES (1, 'default', 'import_queue', 'Default Import Queue', 'IMPORT', 2, 4, 20, 'IMPORT', 'ingest', 'FAIR_SHARE', 5, true, 'Import queue template — adjust max_running_jobs / max_qps before use', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'core', 1, 'SLIDING_WINDOW', 3);
INSERT INTO batch.resource_queue (id, tenant_id, queue_code, queue_name, queue_type, max_running_jobs, max_running_partitions, max_qps, worker_group, resource_tag, priority_policy, fair_share_weight, enabled, description, created_at, updated_at, fair_share_group, burst_limit, quota_reset_policy, group_shared_max_running_jobs) VALUES (2, 'default', 'export_queue', 'Default Export Queue', 'EXPORT', 1, 2, 10, 'EXPORT', 'report', 'PRIORITY', 4, true, 'Export queue template — adjust max_running_jobs / max_qps before use', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'core', 1, 'CALENDAR_DAY', 2);
INSERT INTO batch.resource_queue (id, tenant_id, queue_code, queue_name, queue_type, max_running_jobs, max_running_partitions, max_qps, worker_group, resource_tag, priority_policy, fair_share_weight, enabled, description, created_at, updated_at, fair_share_group, burst_limit, quota_reset_policy, group_shared_max_running_jobs) VALUES (3, 'default', 'dispatch_queue', 'Default Dispatch Queue', 'DISPATCH', 2, 4, 15, 'DISPATCH', 'delivery', 'FIFO', 3, true, 'Dispatch queue template — adjust max_running_jobs / max_qps before use', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'delivery', 1, 'NONE', 2);





























INSERT INTO batch.tenant (id, tenant_id, tenant_name, status, description, created_at, updated_at, created_by) VALUES (1, 'default-tenant', 'default-tenant', 'ACTIVE', 'Migrated from console_user_account', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system');
INSERT INTO batch.tenant (id, tenant_id, tenant_name, status, description, created_at, updated_at, created_by) VALUES (2, 'system', 'System', 'ACTIVE', 'Built-in system management tenant', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system');
INSERT INTO batch.tenant (id, tenant_id, tenant_name, status, description, created_at, updated_at, created_by) VALUES (3, 'default', 'Default Template', 'ACTIVE', 'Template tenant for new tenant config initialization', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system');



INSERT INTO batch.tenant_quota_policy (id, tenant_id, policy_code, max_running_jobs_per_tenant, max_partitions_per_tenant, max_qps_per_tenant, fair_share_weight, enabled, description, created_at, updated_at, fair_share_group, burst_limit, partition_burst_limit, quota_reset_policy, group_shared_max_running_jobs, exceeded_strategy) VALUES (1, 'default', 'default-policy', 5, 10, 30, 1, true, 'Conservative default quota policy — raise limits after capacity planning', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'core', 1, 2, 'SLIDING_WINDOW', 3, 'REJECT');











INSERT INTO batch.waiting_partition_scan_cursor (singleton_id, last_partition_id, version, updated_at, cycle_upper_id, cycle_dry_run) VALUES (1, 0, 1, CURRENT_TIMESTAMP, 0, false);













































SELECT pg_catalog.setval('batch.alert_escalation_notification_outbox_id_seq', 1, false);


SELECT pg_catalog.setval('batch.alert_event_id_seq', 1, false);


SELECT pg_catalog.setval('batch.alert_routing_config_id_seq', 1, false);


SELECT pg_catalog.setval('batch.api_key_id_seq', 1, false);


SELECT pg_catalog.setval('batch.approval_command_id_seq', 1, false);


SELECT pg_catalog.setval('batch.archive_policy_id_seq', 12, true);


SELECT pg_catalog.setval('batch.asset_freshness_policy_id_seq', 1, false);


SELECT pg_catalog.setval('batch.asset_partition_id_seq', 1, false);


SELECT pg_catalog.setval('batch.atomic_task_config_id_seq', 1, false);


SELECT pg_catalog.setval('batch.batch_day_catchup_control_event_id_seq', 1, false);


SELECT pg_catalog.setval('batch.batch_day_instance_id_seq', 1, false);


SELECT pg_catalog.setval('batch.batch_day_operation_audit_id_seq', 1, false);


SELECT pg_catalog.setval('batch.batch_day_replay_entry_id_seq', 1, false);


SELECT pg_catalog.setval('batch.batch_day_replay_session_id_seq', 1, false);


SELECT pg_catalog.setval('batch.batch_day_waiting_launch_id_seq', 1, false);


SELECT pg_catalog.setval('batch.batch_runtime_default_parameter_id_seq', 45, true);


SELECT pg_catalog.setval('batch.batch_window_id_seq', 1, true);


SELECT pg_catalog.setval('batch.biz_table_schema_id_seq', 1, false);


SELECT pg_catalog.setval('batch.business_calendar_id_seq', 1, true);


SELECT pg_catalog.setval('batch.calendar_dependency_id_seq', 1, false);


SELECT pg_catalog.setval('batch.calendar_dependency_wait_id_seq', 1, false);


SELECT pg_catalog.setval('batch.calendar_group_id_seq', 1, false);


SELECT pg_catalog.setval('batch.calendar_holiday_id_seq', 1, false);


SELECT pg_catalog.setval('batch.compensation_command_id_seq', 1, false);


SELECT pg_catalog.setval('batch.config_approval_id_seq', 1, false);


SELECT pg_catalog.setval('batch.config_cache_invalidation_outbox_id_seq', 1, false);


SELECT pg_catalog.setval('batch.config_change_log_id_seq', 1, false);


SELECT pg_catalog.setval('batch.config_release_id_seq', 1, false);


SELECT pg_catalog.setval('batch.config_sync_log_id_seq', 1, false);


SELECT pg_catalog.setval('batch.console_ai_audit_log_id_seq', 1, false);


SELECT pg_catalog.setval('batch.console_ai_turn_id_seq', 1, false);


SELECT pg_catalog.setval('batch.console_external_identity_id_seq', 1, false);


SELECT pg_catalog.setval('batch.console_operation_audit_id_seq', 1, false);


SELECT pg_catalog.setval('batch.console_push_approval_notification_id_seq', 1, false);


SELECT pg_catalog.setval('batch.console_push_job_delivery_id_seq', 1, false);


SELECT pg_catalog.setval('batch.console_push_subscription_id_seq', 1, false);


SELECT pg_catalog.setval('batch.console_usage_daily_id_seq', 1, false);


SELECT pg_catalog.setval('batch.console_user_account_id_seq', 4, true);


SELECT pg_catalog.setval('batch.custom_task_type_registry_id_seq', 1, false);


SELECT pg_catalog.setval('batch.data_asset_id_seq', 1, false);


SELECT pg_catalog.setval('batch.data_quality_check_id_seq', 1, false);


SELECT pg_catalog.setval('batch.data_quality_rule_id_seq', 1, false);


SELECT pg_catalog.setval('batch.dead_letter_task_id_seq', 1, false);


SELECT pg_catalog.setval('batch.disaster_day_override_id_seq', 1, false);


SELECT pg_catalog.setval('batch.event_delivery_log_id_seq', 1, false);


SELECT pg_catalog.setval('batch.event_outbox_retry_id_seq', 1, false);


SELECT pg_catalog.setval('batch.expected_work_occurrence_id_seq', 1, false);


SELECT pg_catalog.setval('batch.external_event_delivery_id_seq', 1, false);


SELECT pg_catalog.setval('batch.external_event_expectation_definition_id_seq', 1, false);


SELECT pg_catalog.setval('batch.file_audit_log_id_seq', 1, false);


SELECT pg_catalog.setval('batch.file_channel_config_id_seq', 1, false);


SELECT pg_catalog.setval('batch.file_channel_health_id_seq', 1, false);


SELECT pg_catalog.setval('batch.file_dispatch_record_id_seq', 1, false);


SELECT pg_catalog.setval('batch.file_error_record_id_seq', 1, false);


SELECT pg_catalog.setval('batch.file_group_expectation_definition_id_seq', 1, false);


SELECT pg_catalog.setval('batch.file_record_id_seq', 1, false);


SELECT pg_catalog.setval('batch.file_template_config_id_seq', 2, true);


SELECT pg_catalog.setval('batch.forensic_export_log_id_seq', 1, false);


SELECT pg_catalog.setval('batch.idempotency_record_id_seq', 1, false);


SELECT pg_catalog.setval('batch.import_ingress_ownership_binding_id_seq', 1, false);


SELECT pg_catalog.setval('batch.import_ingress_scan_object_id_seq', 1, false);


SELECT pg_catalog.setval('batch.job_definition_id_seq', 1, false);


SELECT pg_catalog.setval('batch.job_dependency_waiting_launch_id_seq', 1, false);


SELECT pg_catalog.setval('batch.job_execution_log_id_seq', 1, false);


SELECT pg_catalog.setval('batch.job_instance_id_seq', 1, true);


SELECT pg_catalog.setval('batch.job_monitoring_policy_id_seq', 1, false);


SELECT pg_catalog.setval('batch.job_partition_id_seq', 1, false);


SELECT pg_catalog.setval('batch.job_step_instance_id_seq', 1, false);


SELECT pg_catalog.setval('batch.job_task_id_seq', 1, false);


SELECT pg_catalog.setval('batch.notification_channel_id_seq', 1, false);


SELECT pg_catalog.setval('batch.notification_delivery_log_id_seq', 1, false);


SELECT pg_catalog.setval('batch.outbox_event_id_seq', 1, true);


SELECT pg_catalog.setval('batch.pipeline_instance_id_seq', 1, false);


SELECT pg_catalog.setval('batch.pipeline_progress_id_seq', 1, false);


SELECT pg_catalog.setval('batch.pipeline_step_run_id_seq', 1, false);


SELECT pg_catalog.setval('batch.quota_runtime_state_id_seq', 1, false);


SELECT pg_catalog.setval('batch.readiness_wait_id_seq', 1, false);


SELECT pg_catalog.setval('batch.replay_operation_id_seq', 1, false);


SELECT pg_catalog.setval('batch.resource_queue_id_seq', 3, true);


SELECT pg_catalog.setval('batch.resource_tag_id_seq', 1, false);


SELECT pg_catalog.setval('batch.result_version_id_seq', 1, false);


SELECT pg_catalog.setval('batch.retry_schedule_id_seq', 1, false);


SELECT pg_catalog.setval('batch.scheduled_occurrence_catch_up_audit_id_seq', 1, false);


SELECT pg_catalog.setval('batch.scheduled_occurrence_fire_id_seq', 1, false);


SELECT pg_catalog.setval('batch.scheduled_occurrence_id_seq', 1, false);


SELECT pg_catalog.setval('batch.scheduled_occurrence_supersession_history_id_seq', 1, false);


SELECT pg_catalog.setval('batch.secret_version_id_seq', 1, false);


SELECT pg_catalog.setval('batch.stateful_backend_cutover_history_id_seq', 1, false);


SELECT pg_catalog.setval('batch.step_registry_id_seq', 1, false);


SELECT pg_catalog.setval('batch.subscription_rule_id_seq', 1, false);


SELECT pg_catalog.setval('batch.system_parameter_id_seq', 1, false);


SELECT pg_catalog.setval('batch.tenant_id_seq', 3, true);


SELECT pg_catalog.setval('batch.tenant_quota_policy_id_seq', 1, true);


SELECT pg_catalog.setval('batch.tenant_scheduler_snapshot_id_seq', 1, false);


SELECT pg_catalog.setval('batch.trigger_launch_recovery_id_seq', 1, false);


SELECT pg_catalog.setval('batch.trigger_misfire_pending_id_seq', 1, false);


SELECT pg_catalog.setval('batch.trigger_outbox_event_id_seq', 1, false);


SELECT pg_catalog.setval('batch.trigger_request_id_seq', 1, false);


SELECT pg_catalog.setval('batch.webhook_delivery_log_id_seq', 1, false);


SELECT pg_catalog.setval('batch.webhook_subscription_id_seq', 1, false);


SELECT pg_catalog.setval('batch.worker_registry_id_seq', 1, false);


SELECT pg_catalog.setval('batch.worker_report_outbox_id_seq', 1, false);


SELECT pg_catalog.setval('batch.workflow_definition_id_seq', 1, false);


SELECT pg_catalog.setval('batch.workflow_definition_version_id_seq', 1, false);


SELECT pg_catalog.setval('batch.workflow_edge_id_seq', 1, false);


SELECT pg_catalog.setval('batch.workflow_launch_wait_id_seq', 1, false);


SELECT pg_catalog.setval('batch.workflow_node_id_seq', 1, false);


SELECT pg_catalog.setval('batch.workflow_node_run_id_seq', 1, false);


SELECT pg_catalog.setval('batch.workflow_run_id_seq', 1, false);








SET default_tablespace = '';

ALTER TABLE ONLY archive.workflow_run_archive
    ADD CONSTRAINT uk_workflow_run_archive_tenant_id UNIQUE (tenant_id, id);


ALTER TABLE ONLY batch.forensic_export_log
    ADD CONSTRAINT forensic_export_log_tenant_id_export_id_key UNIQUE (tenant_id, export_id);


ALTER TABLE ONLY batch.import_ingress_ownership
    ADD CONSTRAINT import_ingress_ownership_storage_id_object_prefix_key UNIQUE (storage_id, object_prefix);


ALTER TABLE ONLY batch.alert_escalation_notification_outbox
    ADD CONSTRAINT uk_alert_escalation_notification_outbox UNIQUE (tenant_id, alert_event_id, escalation_tier);


ALTER TABLE ONLY batch.alert_event
    ADD CONSTRAINT uk_alert_event_dedup UNIQUE (tenant_id, dedup_fingerprint);


ALTER TABLE ONLY batch.alert_routing_config
    ADD CONSTRAINT uk_alert_routing_config UNIQUE (tenant_id, route_code);


ALTER TABLE ONLY batch.api_key
    ADD CONSTRAINT uk_api_key_hash UNIQUE (key_hash);


ALTER TABLE ONLY batch.api_key
    ADD CONSTRAINT uk_api_key_tenant_name UNIQUE (tenant_id, key_name);


ALTER TABLE ONLY batch.approval_command
    ADD CONSTRAINT uk_approval_command_tenant_no UNIQUE (tenant_id, approval_no);


ALTER TABLE ONLY batch.archive_policy
    ADD CONSTRAINT uk_archive_policy_tenant_table UNIQUE (tenant_id, target_table);


ALTER TABLE ONLY batch.asset_freshness_policy
    ADD CONSTRAINT uk_asset_freshness_policy_tenant_asset UNIQUE (tenant_id, asset_code, asset_type);


ALTER TABLE ONLY batch.asset_partition
    ADD CONSTRAINT uk_asset_partition_tenant_asset_partition UNIQUE (tenant_id, asset_code, partition_key);


ALTER TABLE ONLY batch.atomic_task_config
    ADD CONSTRAINT uk_atomic_task_config_tenant_type_name UNIQUE (tenant_id, task_type, name);


ALTER TABLE ONLY batch.batch_day_instance
    ADD CONSTRAINT uk_batch_day_instance UNIQUE (tenant_id, calendar_code, biz_date);


ALTER TABLE ONLY batch.batch_day_replay_session
    ADD CONSTRAINT uk_batch_day_replay_session_tenant_id UNIQUE (tenant_id, id);


ALTER TABLE ONLY batch.batch_day_waiting_launch
    ADD CONSTRAINT uk_batch_day_waiting_launch_request UNIQUE (tenant_id, request_id);


ALTER TABLE ONLY batch.batch_runtime_default_parameter
    ADD CONSTRAINT uk_batch_runtime_default_parameter UNIQUE (module, parameter_key);


ALTER TABLE ONLY batch.batch_window
    ADD CONSTRAINT uk_batch_window_tenant_code UNIQUE (tenant_id, window_code);


ALTER TABLE ONLY batch.biz_table_schema
    ADD CONSTRAINT uk_biz_table_schema UNIQUE (schema_name, table_name);


ALTER TABLE ONLY batch.business_calendar
    ADD CONSTRAINT uk_business_calendar_tenant_code UNIQUE (tenant_id, calendar_code);


ALTER TABLE ONLY batch.business_calendar
    ADD CONSTRAINT uk_business_calendar_tenant_group_id UNIQUE (tenant_id, group_code, id);


COMMENT ON CONSTRAINT uk_business_calendar_tenant_group_id ON batch.business_calendar IS '供同租户日历组代表日历复合外键引用';


ALTER TABLE ONLY batch.business_calendar
    ADD CONSTRAINT uk_business_calendar_tenant_id UNIQUE (tenant_id, id);


ALTER TABLE ONLY batch.calendar_dependency
    ADD CONSTRAINT uk_calendar_dependency UNIQUE (tenant_id, upstream_code, downstream_code);


ALTER TABLE ONLY batch.calendar_dependency_wait
    ADD CONSTRAINT uk_calendar_dependency_wait UNIQUE (tenant_id, calendar_code, biz_date);


ALTER TABLE ONLY batch.calendar_group
    ADD CONSTRAINT uk_calendar_group_tenant_code UNIQUE (tenant_id, group_code);


ALTER TABLE ONLY batch.calendar_holiday
    ADD CONSTRAINT uk_calendar_holiday UNIQUE (tenant_id, calendar_id, biz_date);


ALTER TABLE ONLY batch.compensation_command
    ADD CONSTRAINT uk_compensation_command_tenant_no UNIQUE (tenant_id, command_no);


ALTER TABLE ONLY batch.config_batch_operation
    ADD CONSTRAINT uk_config_batch_operation_request UNIQUE (request_identity_hash);


ALTER TABLE ONLY batch.config_release
    ADD CONSTRAINT uk_config_release_tenant_type_key_version UNIQUE (tenant_id, config_type, config_key, version_no);


ALTER TABLE ONLY batch.console_ai_attachment
    ADD CONSTRAINT uk_console_ai_attachment_client UNIQUE (tenant_id, owner_user_id, client_attachment_id);


ALTER TABLE ONLY batch.console_ai_attachment
    ADD CONSTRAINT uk_console_ai_attachment_object UNIQUE (object_key);


ALTER TABLE ONLY batch.console_ai_audit_log
    ADD CONSTRAINT uk_console_ai_audit_request UNIQUE (tenant_id, request_id);


ALTER TABLE ONLY batch.console_ai_turn
    ADD CONSTRAINT uk_console_ai_turn_sequence UNIQUE (tenant_id, conversation_id, turn_no);


ALTER TABLE ONLY batch.console_external_identity
    ADD CONSTRAINT uk_console_external_identity_account UNIQUE (tenant_id, account_id);


ALTER TABLE ONLY batch.console_external_identity
    ADD CONSTRAINT uk_console_external_identity_subject UNIQUE (tenant_id, issuer, subject);


ALTER TABLE ONLY batch.console_user_account
    ADD CONSTRAINT uk_console_user_account_username UNIQUE (username);


ALTER TABLE ONLY batch.console_user_batch_operation
    ADD CONSTRAINT uk_console_user_batch_actor_request UNIQUE (actor_username, request_id);


ALTER TABLE ONLY batch.custom_task_type_registry
    ADD CONSTRAINT uk_custom_task_type_registry_tenant_code UNIQUE (tenant_id, task_type_code);


ALTER TABLE ONLY batch.data_asset
    ADD CONSTRAINT uk_data_asset_tenant_code_type UNIQUE (tenant_id, asset_code, asset_type);


ALTER TABLE ONLY batch.data_quality_rule
    ADD CONSTRAINT uk_dq_rule_tenant_code UNIQUE (tenant_id, rule_code);


ALTER TABLE ONLY batch.expected_work_occurrence
    ADD CONSTRAINT uk_expected_work_identity UNIQUE (tenant_id, calendar_code, workflow_definition_id, source_type, source_key, biz_date, generation);


ALTER TABLE ONLY batch.external_event_delivery
    ADD CONSTRAINT uk_external_event_delivery_identity UNIQUE (tenant_id, event_source, event_id);


ALTER TABLE ONLY batch.external_event_delivery
    ADD CONSTRAINT uk_external_event_delivery_request UNIQUE (tenant_id, request_id);


ALTER TABLE ONLY batch.external_event_delivery
    ADD CONSTRAINT uk_external_event_delivery_tenant_id UNIQUE (tenant_id, id);


ALTER TABLE ONLY batch.external_event_expectation_definition
    ADD CONSTRAINT uk_external_event_expectation_definition UNIQUE (tenant_id, calendar_code, workflow_definition_id, event_code);


ALTER TABLE ONLY batch.file_channel_config
    ADD CONSTRAINT uk_file_channel_config UNIQUE (tenant_id, channel_code);


ALTER TABLE ONLY batch.file_channel_health
    ADD CONSTRAINT uk_file_channel_health UNIQUE (tenant_id, channel_code);


ALTER TABLE ONLY batch.file_group_expectation_definition
    ADD CONSTRAINT uk_file_group_expectation_definition UNIQUE (tenant_id, calendar_code, workflow_definition_id, file_group_code);


ALTER TABLE ONLY batch.file_template_config
    ADD CONSTRAINT uk_file_template_tenant_code_version UNIQUE (tenant_id, template_code, version);


ALTER TABLE ONLY batch.import_ingress_scan_object
    ADD CONSTRAINT uk_import_ingress_scan_object UNIQUE (scope_id, object_digest);


ALTER TABLE ONLY batch.job_definition
    ADD CONSTRAINT uk_job_definition_tenant_code UNIQUE (tenant_id, job_code);


ALTER TABLE ONLY batch.job_dependency
    ADD CONSTRAINT uk_job_dependency_order UNIQUE (tenant_id, downstream_job_code, dependency_order);


ALTER TABLE ONLY batch.job_dependency_waiting_launch
    ADD CONSTRAINT uk_job_dependency_wait_request UNIQUE (tenant_id, request_id);


ALTER TABLE ONLY batch.job_instance
    ADD CONSTRAINT uk_job_instance_tenant_dedup_attempt UNIQUE (tenant_id, dedup_key, run_attempt, biz_date);


ALTER TABLE ONLY batch.job_instance
    ADD CONSTRAINT uk_job_instance_tenant_instance_no UNIQUE (tenant_id, instance_no, biz_date);


ALTER TABLE ONLY batch.job_monitoring_policy
    ADD CONSTRAINT uk_job_monitoring_policy_tenant_definition UNIQUE (tenant_id, job_definition_id);


ALTER TABLE ONLY batch.job_partition
    ADD CONSTRAINT uk_job_partition_instance_no UNIQUE (job_instance_id, partition_no);


ALTER TABLE ONLY batch.job_step_instance
    ADD CONSTRAINT uk_job_step_instance_task UNIQUE (tenant_id, job_task_id);


ALTER TABLE ONLY batch.notification_channel
    ADD CONSTRAINT uk_notification_channel_tenant_code UNIQUE (tenant_id, channel_code);


ALTER TABLE ONLY batch.outbox_event
    ADD CONSTRAINT uk_outbox_event_key UNIQUE (tenant_id, event_key, created_at);


ALTER TABLE ONLY batch.outbox_event
    ADD CONSTRAINT uk_outbox_event_tenant_id_created_at UNIQUE (tenant_id, id, created_at);


ALTER TABLE ONLY batch.pipeline_progress
    ADD CONSTRAINT uk_pipeline_progress_tenant_instance_stage UNIQUE (tenant_id, pipeline_instance_id, stage);


ALTER TABLE ONLY batch.pipeline_step_run
    ADD CONSTRAINT uk_pipeline_step_run UNIQUE (pipeline_instance_id, step_code, run_seq);


ALTER TABLE ONLY batch.quota_runtime_state
    ADD CONSTRAINT uk_quota_runtime_state UNIQUE (tenant_id, quota_scope, owner_code);


ALTER TABLE ONLY batch.readiness_wait
    ADD CONSTRAINT uk_readiness_wait_identity UNIQUE (tenant_id, workflow_definition_id, scheduled_fire_time);


ALTER TABLE ONLY batch.replay_operation
    ADD CONSTRAINT uk_replay_operation_identity UNIQUE (tenant_id, operation_key);


ALTER TABLE ONLY batch.replay_operation_preview_token
    ADD CONSTRAINT uk_replay_operation_preview_identity UNIQUE (tenant_id, operation_key);


ALTER TABLE ONLY batch.replay_operation
    ADD CONSTRAINT uk_replay_operation_tenant_id UNIQUE (tenant_id, id);


ALTER TABLE ONLY batch.resource_queue
    ADD CONSTRAINT uk_resource_queue_tenant_code UNIQUE (tenant_id, queue_code);


ALTER TABLE ONLY batch.resource_tag
    ADD CONSTRAINT uk_resource_tag UNIQUE (tenant_id, resource_type, resource_code, tag_key);


ALTER TABLE ONLY batch.retry_schedule
    ADD CONSTRAINT uk_retry_schedule_tenant_dedup UNIQUE (tenant_id, dedup_key);


ALTER TABLE ONLY batch.scheduled_occurrence_catch_up_audit
    ADD CONSTRAINT uk_scheduled_occurrence_catch_up UNIQUE (tenant_id, workflow_definition_id, scheduled_fire_time);


ALTER TABLE ONLY batch.scheduled_occurrence
    ADD CONSTRAINT uk_scheduled_occurrence_fire UNIQUE (tenant_id, workflow_definition_id, scheduled_fire_time);


ALTER TABLE ONLY batch.scheduled_occurrence_fire
    ADD CONSTRAINT uk_scheduled_occurrence_fire_event UNIQUE (tenant_id, workflow_definition_id, scheduled_fire_time, fire_instance_id, refire_count);


ALTER TABLE ONLY batch.scheduled_occurrence
    ADD CONSTRAINT uk_scheduled_occurrence_request_identity UNIQUE (tenant_id, workflow_definition_id, scheduled_fire_time, biz_date, request_id);


ALTER TABLE ONLY batch.secret_version
    ADD CONSTRAINT uk_secret_version_tenant_ref_version UNIQUE (tenant_id, secret_ref, version_no);


ALTER TABLE ONLY batch.step_registry
    ADD CONSTRAINT uk_step_registry_module_impl UNIQUE (module, impl_code);


ALTER TABLE ONLY batch.subscription_rule
    ADD CONSTRAINT uk_subscription_rule_tenant_name UNIQUE (tenant_id, rule_name);


ALTER TABLE ONLY batch.system_parameter
    ADD CONSTRAINT uk_system_parameter_tenant_key UNIQUE (tenant_id, param_key);


ALTER TABLE ONLY batch.tenant_quota_policy
    ADD CONSTRAINT uk_tenant_quota_policy UNIQUE (tenant_id, policy_code);


ALTER TABLE ONLY batch.tenant
    ADD CONSTRAINT uk_tenant_tenant_id UNIQUE (tenant_id);


ALTER TABLE ONLY batch.trigger_launch_recovery
    ADD CONSTRAINT uk_trigger_launch_recovery_request UNIQUE (tenant_id, request_id);


ALTER TABLE ONLY batch.trigger_outbox_event
    ADD CONSTRAINT uk_trigger_outbox_event_tenant_request UNIQUE (tenant_id, request_id);


ALTER TABLE ONLY batch.trigger_request
    ADD CONSTRAINT uk_trigger_request_tenant_request UNIQUE (tenant_id, request_id);


ALTER TABLE ONLY batch.webhook_subscription
    ADD CONSTRAINT uk_webhook_subscription_tenant_name UNIQUE (tenant_id, name);


ALTER TABLE ONLY batch.worker_registry
    ADD CONSTRAINT uk_worker_registry_tenant_worker UNIQUE (tenant_id, worker_code);


ALTER TABLE ONLY batch.workflow_definition
    ADD CONSTRAINT uk_workflow_definition_tenant_code_version UNIQUE (tenant_id, workflow_code, version);


ALTER TABLE ONLY batch.workflow_definition_version
    ADD CONSTRAINT uk_workflow_definition_version_tenant_def_ver UNIQUE (tenant_id, workflow_definition_id, version);


ALTER TABLE ONLY batch.workflow_edge
    ADD CONSTRAINT uk_workflow_edge UNIQUE (tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type);


ALTER TABLE ONLY batch.workflow_launch_wait
    ADD CONSTRAINT uk_workflow_launch_wait_request UNIQUE (tenant_id, request_id);


ALTER TABLE ONLY batch.workflow_node
    ADD CONSTRAINT uk_workflow_node_def_code UNIQUE (tenant_id, workflow_definition_id, node_code);


ALTER TABLE ONLY batch.workflow_node_run
    ADD CONSTRAINT uk_workflow_node_run UNIQUE (workflow_run_id, node_code, run_seq);


ALTER TABLE ONLY batch.workflow_run
    ADD CONSTRAINT uk_workflow_run_tenant_id UNIQUE (tenant_id, id);


ALTER TABLE ONLY batch.console_push_approval_notification
    ADD CONSTRAINT uq_console_push_approval_notification UNIQUE (tenant_id, approval_no);


ALTER TABLE ONLY batch.console_push_subscription
    ADD CONSTRAINT uq_console_push_subscription UNIQUE (tenant_id, username, endpoint);


ALTER TABLE ONLY batch.idempotency_record
    ADD CONSTRAINT uq_idempotency_key UNIQUE (tenant_id, idempotency_key);


ALTER TABLE ONLY batch.console_push_job_delivery
    ADD CONSTRAINT uq_job_push_recipient UNIQUE NULLS NOT DISTINCT (tenant_id, terminal_event_key, subscription_id);


ALTER TABLE ONLY batch.worker_report_outbox
    ADD CONSTRAINT uq_worker_report_outbox_tenant_task_invocation UNIQUE (tenant_id, task_id, partition_invocation_id);


COMMENT ON CONSTRAINT uq_worker_report_outbox_tenant_task_invocation ON batch.worker_report_outbox IS '同租户、同任务、同执行尝试去重；空执行身份使用空串，不覆盖其他尝试';


CREATE UNIQUE INDEX batch_day_replay_entry_archiv_session_id_tenant_id_job_code_idx ON archive.batch_day_replay_entry_archive USING btree (session_id, tenant_id, job_code);


CREATE INDEX batch_day_replay_entry_archive_rerun_instance_id_idx ON archive.batch_day_replay_entry_archive USING btree (rerun_instance_id) WHERE (rerun_instance_id IS NOT NULL);


CREATE INDEX batch_day_replay_entry_archive_session_id_status_idx ON archive.batch_day_replay_entry_archive USING btree (session_id, status);


CREATE INDEX batch_day_replay_session_arch_tenant_id_calendar_code_biz__idx1 ON archive.batch_day_replay_session_archive USING btree (tenant_id, calendar_code, biz_date, created_at DESC);


CREATE UNIQUE INDEX batch_day_replay_session_arch_tenant_id_calendar_code_biz_d_idx ON archive.batch_day_replay_session_archive USING btree (tenant_id, calendar_code, biz_date) WHERE ((status)::text = ANY (ARRAY['PENDING_APPROVAL'::character varying, 'RUNNING'::character varying]));


CREATE INDEX batch_day_replay_session_archiv_tenant_id_status_created_at_idx ON archive.batch_day_replay_session_archive USING btree (tenant_id, status, created_at DESC);


CREATE INDEX idx_console_operation_audit_cold_tenant_time ON archive.console_operation_audit_archive USING btree (tenant_id, created_at DESC);


CREATE INDEX idx_console_push_job_delivery_cold_receipt ON archive.console_push_job_delivery_archive USING btree (tenant_id, terminal_event_key);


CREATE INDEX idx_console_push_job_delivery_cold_tenant_time ON archive.console_push_job_delivery_archive USING btree (tenant_id, created_at DESC);


CREATE INDEX idx_data_quality_check_archive_tenant_instance ON archive.data_quality_check_archive USING btree (tenant_id, job_instance_id, id);


CREATE INDEX idx_dead_letter_task_archive_replay_status_created ON archive.dead_letter_task_archive USING btree (replay_status, created_at);


CREATE INDEX idx_event_delivery_log_archive_outbox ON archive.event_delivery_log_archive USING btree (outbox_event_id);


CREATE INDEX idx_event_outbox_retry_archive_outbox ON archive.event_outbox_retry_archive USING btree (outbox_event_id);


CREATE INDEX idx_file_record_archive_biz_date ON archive.file_record_archive USING btree (tenant_id, biz_date, created_at);


CREATE INDEX idx_file_record_archive_status ON archive.file_record_archive USING btree (tenant_id, file_status, updated_at);


CREATE INDEX idx_file_record_archive_trace_id ON archive.file_record_archive USING btree (trace_id);


CREATE INDEX idx_job_instance_archive_effective_watermark ON archive.job_instance_archive USING btree (tenant_id, job_definition_id, finished_at DESC NULLS LAST, id DESC) WHERE (((instance_status)::text = 'SUCCESS'::text) AND (replay_session_id IS NULL) AND (rerun_flag = false) AND (high_water_mark_out IS NOT NULL) AND ((high_water_mark_out)::text <> ''::text));


CREATE INDEX idx_job_instance_archive_finished ON archive.job_instance_archive USING btree (tenant_id, instance_status, finished_at);


CREATE INDEX idx_job_partition_archive_instance ON archive.job_partition_archive USING btree (job_instance_id);


CREATE INDEX idx_job_step_instance_archive_instance ON archive.job_step_instance_archive USING btree (job_instance_id);


CREATE INDEX idx_job_task_archive_instance ON archive.job_task_archive USING btree (job_instance_id);


CREATE INDEX idx_notification_delivery_log_cold_receipt ON archive.notification_delivery_log_archive USING btree (tenant_id, terminal_event_key);


CREATE INDEX idx_notification_delivery_log_cold_tenant_time ON archive.notification_delivery_log_archive USING btree (tenant_id, created_at DESC);


CREATE INDEX idx_outbox_event_archive_created ON archive.outbox_event_archive USING btree (publish_status, created_at);


CREATE INDEX idx_trigger_outbox_event_archive_created ON archive.trigger_outbox_event_archive USING btree (publish_status, created_at);


CREATE INDEX idx_webhook_delivery_log_cold_receipt ON archive.webhook_delivery_log_archive USING btree (tenant_id, terminal_event_key);


CREATE INDEX idx_webhook_delivery_log_cold_tenant_time ON archive.webhook_delivery_log_archive USING btree (tenant_id, created_at DESC);


CREATE INDEX idx_workflow_run_archive_trigger_request ON archive.workflow_run_archive USING btree (tenant_id, trigger_request_id) WHERE (trigger_request_id IS NOT NULL);


CREATE UNIQUE INDEX result_version_archive_tenant_id_business_key_idx ON archive.result_version_archive USING btree (tenant_id, business_key) WHERE ((status)::text = 'EFFECTIVE'::text);


CREATE INDEX result_version_archive_tenant_id_business_key_status_idx ON archive.result_version_archive USING btree (tenant_id, business_key, status);


CREATE UNIQUE INDEX result_version_archive_tenant_id_business_key_version_no_idx ON archive.result_version_archive USING btree (tenant_id, business_key, version_no);


CREATE INDEX result_version_archive_tenant_id_job_instance_id_idx ON archive.result_version_archive USING btree (tenant_id, job_instance_id);


CREATE INDEX result_version_archive_tenant_id_status_generated_at_idx ON archive.result_version_archive USING btree (tenant_id, status, generated_at DESC);


CREATE UNIQUE INDEX uk_compensation_command_archive_tenant_approval ON archive.compensation_command_archive USING btree (tenant_id, approval_id) WHERE (approval_id IS NOT NULL);


CREATE UNIQUE INDEX uq_terminal_notification_retry_cold_request ON archive.console_operation_audit_archive USING btree (tenant_id, aggregate_id, request_id) WHERE (((aggregate_type)::text = 'NOTIFICATION_DELIVERY'::text) AND ((action)::text = 'NOTIFICATION_DELIVERY_RETRY'::text));


COMMENT ON INDEX archive.uq_terminal_notification_retry_cold_request IS '终态投递人工重试的原请求身份；归档后仍禁止同一请求重新开启发送预算。';


CREATE INDEX idx_alert_escalation_notification_outbox_poll ON batch.alert_escalation_notification_outbox USING btree (publish_status, next_publish_at, id);


CREATE INDEX idx_alert_event_escalation_notify ON batch.alert_event USING btree (escalation_tier, escalation_notified_tier) WHERE ((status)::text = 'OPEN'::text);


CREATE INDEX idx_alert_event_escalation_scan ON batch.alert_event USING btree (last_seen_at) WHERE ((status)::text = 'OPEN'::text);


CREATE INDEX idx_alert_event_tenant_last_seen ON batch.alert_event USING btree (tenant_id, last_seen_at DESC);


CREATE INDEX idx_alert_event_type_severity ON batch.alert_event USING btree (tenant_id, alert_type, severity);


CREATE INDEX idx_alert_routing_config_active ON batch.alert_routing_config USING btree (tenant_id, route_code) WHERE (is_deleted = false);


CREATE INDEX idx_alert_routing_config_team ON batch.alert_routing_config USING btree (tenant_id, team);


CREATE INDEX idx_alert_routing_config_tenant ON batch.alert_routing_config USING btree (tenant_id);


CREATE INDEX idx_alert_routing_tenant_id_desc ON batch.alert_routing_config USING btree (tenant_id, id DESC);


CREATE INDEX idx_api_key_prefix ON batch.api_key USING btree (key_prefix);


CREATE INDEX idx_api_key_tenant ON batch.api_key USING btree (tenant_id, enabled);


CREATE INDEX idx_approval_catch_up_latest ON batch.approval_command USING btree (tenant_id, target_id, id DESC) WHERE ((approval_type)::text = 'CATCH_UP'::text);


COMMENT ON INDEX batch.idx_approval_catch_up_latest IS '补跑请求按租户及目标查询最新审批单的局部索引。';


CREATE INDEX idx_approval_command_status ON batch.approval_command USING btree (tenant_id, approval_status, created_at DESC);


CREATE INDEX idx_asset_freshness_policy_enabled ON batch.asset_freshness_policy USING btree (enabled, tenant_id, asset_code) WHERE (enabled = true);


CREATE INDEX idx_asset_partition_asset_id ON batch.asset_partition USING btree (tenant_id, asset_id);


COMMENT ON INDEX batch.idx_asset_partition_asset_id IS 'asset_id 外键辅助索引(tenant_id 前缀):支撑 data_asset 级联校验与租户内按 asset 反查分区';


CREATE INDEX idx_asset_partition_tenant_asset_biz_date ON batch.asset_partition USING btree (tenant_id, asset_code, biz_date);


CREATE INDEX idx_asset_partition_tenant_business_key ON batch.asset_partition USING btree (tenant_id, business_key);


CREATE INDEX idx_asset_partition_tenant_freshness_updated ON batch.asset_partition USING btree (tenant_id, freshness_status, updated_at DESC);


CREATE INDEX idx_asset_partition_tenant_result_version ON batch.asset_partition USING btree (tenant_id, result_version_id) WHERE (result_version_id IS NOT NULL);


COMMENT ON INDEX batch.idx_asset_partition_tenant_result_version IS '支撑 asset_partition readiness 关联 result_version 校验 EFFECTIVE 与最新版本';


CREATE INDEX idx_atomic_task_config_tenant_type ON batch.atomic_task_config USING btree (tenant_id, task_type);


CREATE INDEX idx_batch_day_catchup_control_event_recent ON batch.batch_day_catchup_control_event USING btree (tenant_id, calendar_code, occurred_at DESC, id DESC);


CREATE INDEX idx_batch_day_instance_frozen ON batch.batch_day_instance USING btree (tenant_id, frozen, biz_date DESC) WHERE (frozen = true);


CREATE INDEX idx_batch_day_instance_status ON batch.batch_day_instance USING btree (day_status, biz_date DESC);


CREATE INDEX idx_batch_day_op_audit_tenant_calendar_date ON batch.batch_day_operation_audit USING btree (tenant_id, calendar_code, biz_date, created_at DESC);


CREATE INDEX idx_batch_day_op_audit_tenant_op_date ON batch.batch_day_operation_audit USING btree (tenant_id, operation_type, created_at DESC);


CREATE INDEX idx_batch_day_waiting_launch_status ON batch.batch_day_waiting_launch USING btree (wait_status, tenant_id, calendar_code, biz_date);


CREATE INDEX idx_batch_window_enabled ON batch.batch_window USING btree (tenant_id, enabled);


CREATE INDEX idx_batch_window_tenant_id_desc ON batch.batch_window USING btree (tenant_id, id DESC);


CREATE INDEX idx_biz_table_schema ON batch.biz_table_schema USING btree (schema_name);


CREATE INDEX idx_business_calendar_enabled ON batch.business_calendar USING btree (tenant_id, enabled);


CREATE INDEX idx_business_calendar_tenant_group_reference ON batch.business_calendar USING btree (tenant_id, group_code) WHERE (group_code IS NOT NULL);


CREATE INDEX idx_business_calendar_tenant_id_desc ON batch.business_calendar USING btree (tenant_id, id DESC);


CREATE INDEX idx_calendar_dependency_downstream ON batch.calendar_dependency USING btree (tenant_id, downstream_code, enabled);


CREATE INDEX idx_calendar_dependency_wait_due ON batch.calendar_dependency_wait USING btree (tenant_id, calendar_code, wait_status, last_checked_at, id);


CREATE INDEX idx_calendar_dependency_wait_tenant_id ON batch.calendar_dependency_wait USING btree (tenant_id, id);


CREATE INDEX idx_calendar_holiday_biz_date ON batch.calendar_holiday USING btree (biz_date, day_type);


CREATE INDEX idx_calendar_holiday_scope_group ON batch.calendar_holiday USING btree (scope, group_code, biz_date) WHERE ((scope)::text = 'GROUP'::text);


CREATE INDEX idx_calendar_holiday_tenant ON batch.calendar_holiday USING btree (tenant_id, calendar_id, biz_date);


CREATE INDEX idx_calendar_holiday_tenant_group_calendar_reference ON batch.calendar_holiday USING btree (tenant_id, group_code, calendar_id) WHERE (group_code IS NOT NULL);


CREATE INDEX idx_compensation_command_lookup ON batch.compensation_command USING btree (tenant_id, compensation_type, command_status, created_at DESC);


CREATE INDEX idx_config_approval_release ON batch.config_approval USING btree (release_id);


CREATE INDEX idx_config_approval_tenant_status ON batch.config_approval USING btree (tenant_id, approval_status, created_at DESC);


CREATE INDEX idx_config_cache_invalidation_due ON batch.config_cache_invalidation_outbox USING btree (next_attempt_at, id);


CREATE INDEX idx_config_change_log_tenant_action ON batch.config_change_log USING btree (tenant_id, change_action, created_at DESC);


CREATE INDEX idx_config_release_tenant_status ON batch.config_release USING btree (tenant_id, config_status, created_at DESC);


CREATE INDEX idx_config_sync_log_tenant ON batch.config_sync_log USING btree (tenant_id, created_at DESC);


CREATE INDEX idx_config_sync_receipt_target ON batch.config_sync_target_receipt USING btree (tenant_id, audit_tenant_id, log_id);


CREATE INDEX idx_console_ai_attachment_expiry ON batch.console_ai_attachment USING btree (tenant_id, expires_at);


CREATE INDEX idx_console_ai_attachment_turn ON batch.console_ai_attachment USING btree (tenant_id, conversation_id, turn_no);


CREATE INDEX idx_console_ai_audit_created_at ON batch.console_ai_audit_log USING btree (created_at);


CREATE INDEX idx_console_ai_audit_tenant_id_desc ON batch.console_ai_audit_log USING btree (tenant_id, id DESC);


CREATE INDEX idx_console_ai_conversation_expiry ON batch.console_ai_conversation USING btree (expires_at);


CREATE INDEX idx_console_ai_conversation_owner_updated ON batch.console_ai_conversation USING btree (tenant_id, owner_user_id, updated_at DESC, id DESC);


CREATE INDEX idx_console_ai_turn_history ON batch.console_ai_turn USING btree (tenant_id, conversation_id, turn_no DESC);


CREATE INDEX idx_console_operation_audit_action_time ON batch.console_operation_audit USING btree (action, created_at DESC);


CREATE INDEX idx_console_operation_audit_operator_time ON batch.console_operation_audit USING btree (operator_id, created_at DESC) WHERE (operator_id IS NOT NULL);


CREATE INDEX idx_console_operation_audit_tenant_time ON batch.console_operation_audit USING btree (tenant_id, created_at DESC);


CREATE INDEX idx_console_operation_audit_trace ON batch.console_operation_audit USING btree (trace_id) WHERE (trace_id IS NOT NULL);


CREATE INDEX idx_console_push_approval_notification_notified_at ON batch.console_push_approval_notification USING btree (notified_at);


CREATE INDEX idx_console_push_sub_endpoint ON batch.console_push_subscription USING btree (endpoint);


CREATE INDEX idx_console_push_sub_tenant_user ON batch.console_push_subscription USING btree (tenant_id, username);


CREATE INDEX idx_console_usage_daily_metric_date ON ONLY batch.console_usage_daily USING btree (metric_code, stat_date DESC);


CREATE INDEX idx_console_usage_daily_tenant_date ON ONLY batch.console_usage_daily USING btree (tenant_id, stat_date DESC);


CREATE INDEX idx_console_user_batch_actor_created ON batch.console_user_batch_operation USING btree (actor_username, created_at DESC);


CREATE INDEX idx_custom_task_type_registry_tenant_status ON batch.custom_task_type_registry USING btree (tenant_id, status);


CREATE INDEX idx_data_asset_tenant_type_code ON batch.data_asset USING btree (tenant_id, asset_type, asset_code);


CREATE INDEX idx_dead_letter_auto_retry ON batch.dead_letter_task USING btree (replay_status, error_class, next_replay_at, replay_count) WHERE (((replay_status)::text = ANY (ARRAY['NEW'::character varying, 'FAILED'::character varying])) AND ((error_class)::text = 'SYSTEM'::text));


CREATE INDEX idx_dead_letter_replay_status ON batch.dead_letter_task USING btree (replay_status, created_at);


CREATE INDEX idx_dead_letter_trace_id ON batch.dead_letter_task USING btree (trace_id);


CREATE INDEX idx_disaster_lookup ON batch.disaster_day_override USING btree (tenant_id, calendar_code, biz_date, ttl_until);


CREATE INDEX idx_dq_check_instance ON batch.data_quality_check USING btree (tenant_id, job_instance_id);


CREATE INDEX idx_dq_check_rule_status ON batch.data_quality_check USING btree (tenant_id, rule_id, status, checked_at DESC);


CREATE INDEX idx_dq_rule_scope ON batch.data_quality_rule USING btree (tenant_id, scope_business_key, enabled);


CREATE INDEX idx_event_delivery_log_outbox_event_id ON batch.event_delivery_log USING btree (outbox_event_id);


CREATE INDEX idx_event_delivery_log_outbox_reference ON batch.event_delivery_log USING btree (tenant_id, outbox_event_id, outbox_event_created_at);


CREATE INDEX idx_event_delivery_log_outbox_tenant ON batch.event_delivery_log USING btree (outbox_event_id, tenant_id);


CREATE INDEX idx_event_delivery_log_tenant_id_desc ON batch.event_delivery_log USING btree (tenant_id, id DESC);


CREATE INDEX idx_event_delivery_log_tenant_status ON batch.event_delivery_log USING btree (tenant_id, delivery_status, created_at DESC);


CREATE INDEX idx_event_delivery_log_trace_id ON batch.event_delivery_log USING btree (trace_id);


CREATE INDEX idx_event_outbox_retry_outbox_reference ON batch.event_outbox_retry USING btree (tenant_id, outbox_event_id, outbox_event_created_at);


CREATE INDEX idx_event_outbox_retry_outbox_tenant ON batch.event_outbox_retry USING btree (outbox_event_id, tenant_id);


CREATE INDEX idx_event_outbox_retry_tenant_id_desc ON batch.event_outbox_retry USING btree (tenant_id, id DESC);


CREATE INDEX idx_event_outbox_retry_tenant_status ON batch.event_outbox_retry USING btree (tenant_id, retry_status, created_at DESC);


CREATE INDEX idx_expected_work_day_status ON batch.expected_work_occurrence USING btree (tenant_id, calendar_code, biz_date, occurrence_status);


CREATE INDEX idx_expected_work_due_unalerted ON batch.expected_work_occurrence USING btree (tenant_id, calendar_code, biz_date, due_at, id) WHERE (((occurrence_status)::text = 'EXPECTED'::text) AND (due_at IS NOT NULL) AND (missing_input_alerted_at IS NULL));


CREATE INDEX idx_external_event_delivery_alias_delivery ON batch.external_event_delivery_alias USING btree (tenant_id, delivery_id);


CREATE INDEX idx_external_event_expectation_enabled ON batch.external_event_expectation_definition USING btree (tenant_id, calendar_code, enabled, workflow_definition_id);


CREATE INDEX idx_external_event_expectation_workflow_calendar_fk ON batch.external_event_expectation_definition USING btree (tenant_id, workflow_definition_id, calendar_code);


CREATE INDEX idx_file_audit_operation ON batch.file_audit_log USING btree (tenant_id, operation_type, created_at);


CREATE INDEX idx_file_audit_trace_id ON batch.file_audit_log USING btree (trace_id);


CREATE INDEX idx_file_channel_config_active ON batch.file_channel_config USING btree (tenant_id, channel_code) WHERE (is_deleted = false);


CREATE INDEX idx_file_channel_enabled ON batch.file_channel_config USING btree (tenant_id, channel_type, enabled);


CREATE INDEX idx_file_channel_health_next_probe ON batch.file_channel_health USING btree (health_status, next_probe_at);


CREATE INDEX idx_file_channel_tenant_id_desc ON batch.file_channel_config USING btree (tenant_id, id DESC);


CREATE INDEX idx_file_dispatch_external_request ON batch.file_dispatch_record USING btree (external_request_id);


CREATE INDEX idx_file_dispatch_status ON batch.file_dispatch_record USING btree (tenant_id, dispatch_status, dispatched_at);


CREATE INDEX idx_file_error_record_created_at ON batch.file_error_record USING btree (created_at DESC);


CREATE INDEX idx_file_error_record_tenant_file ON batch.file_error_record USING btree (tenant_id, file_id, error_stage, error_code);


CREATE INDEX idx_file_group_expectation_enabled ON batch.file_group_expectation_definition USING btree (tenant_id, calendar_code, enabled, workflow_definition_id);


CREATE INDEX idx_file_group_expectation_workflow_calendar_fk ON batch.file_group_expectation_definition USING btree (tenant_id, workflow_definition_id, calendar_code);


CREATE INDEX idx_file_record_active_tenant_bizdate ON batch.file_record USING btree (tenant_id, biz_date) WHERE ((file_status)::text <> ALL (ARRAY['DELETED'::character varying, 'ARCHIVED'::character varying]));


COMMENT ON INDEX batch.idx_file_record_active_tenant_bizdate IS 'DBA-2026-05-20 P1-1 — 未删除文件 partial, DataGovernance 列表过滤';


CREATE INDEX idx_file_record_arrival_group_code_expr ON batch.file_record USING btree (tenant_id, ((metadata_json ->> 'fileGroupCode'::text))) WHERE (metadata_json ? 'fileGroupCode'::text);


CREATE INDEX idx_file_record_arrival_state_expr ON batch.file_record USING btree (tenant_id, COALESCE((metadata_json ->> 'arrivalState'::text), 'WAITING_ARRIVAL'::text)) WHERE (metadata_json ? 'fileGroupCode'::text);


CREATE INDEX idx_file_record_biz_date ON batch.file_record USING btree (tenant_id, biz_date, created_at);


CREATE INDEX idx_file_record_biz_type_status ON batch.file_record USING btree (tenant_id, biz_type, file_status);


CREATE INDEX idx_file_record_file_name_trgm ON batch.file_record USING gin (file_name batch.gin_trgm_ops);


CREATE INDEX idx_file_record_latest ON batch.file_record USING btree (tenant_id, is_latest, file_name);


CREATE INDEX idx_file_record_metadata_json_gin ON batch.file_record USING gin (metadata_json);


CREATE INDEX idx_file_record_metadata_json_gin_partial ON batch.file_record USING gin (metadata_json) WHERE (metadata_json IS NOT NULL);


CREATE INDEX idx_file_record_tenant_id_desc ON batch.file_record USING btree (tenant_id, id DESC);


CREATE INDEX idx_file_record_trace_id ON batch.file_record USING btree (trace_id);


CREATE INDEX idx_file_template_config_active ON batch.file_template_config USING btree (tenant_id, template_code) WHERE (is_deleted = false);


CREATE INDEX idx_file_template_enabled_updated ON batch.file_template_config USING btree (enabled, updated_at);


CREATE INDEX idx_file_template_tenant_code_version ON batch.file_template_config USING btree (tenant_id, template_code, version DESC);


CREATE INDEX idx_file_template_type_biz ON batch.file_template_config USING btree (tenant_id, template_type, biz_type);


CREATE INDEX idx_forensic_export_log_tenant_bizdate ON batch.forensic_export_log USING btree (tenant_id, biz_date_from, biz_date_to, requested_at DESC);


CREATE INDEX idx_forensic_export_log_tenant_status ON batch.forensic_export_log USING btree (tenant_id, status, requested_at DESC);


CREATE INDEX idx_import_ingress_ownership_binding_scope ON batch.import_ingress_ownership_binding USING btree (scope_id);


CREATE INDEX idx_import_ingress_scan_manifest_window ON batch.import_ingress_scan_object USING btree (scope_id, seen_window, id) WHERE is_manifest;


CREATE INDEX idx_import_ingress_scan_object_cycle ON batch.import_ingress_scan_object USING btree (scope_id, seen_cycle, id);


CREATE INDEX idx_import_ingress_scan_object_scope_id ON batch.import_ingress_scan_object USING btree (scope_id, id);


CREATE INDEX idx_import_ingress_scan_waiting ON batch.import_ingress_scan_state USING btree (storage_id, waiting_since, scope_id) WHERE (waiting_since IS NOT NULL);


CREATE INDEX idx_job_definition_enabled ON batch.job_definition USING btree (tenant_id, enabled);


CREATE INDEX idx_job_definition_queue ON batch.job_definition USING btree (tenant_id, queue_code, enabled);


CREATE INDEX idx_job_definition_tenant_group ON batch.job_definition USING btree (tenant_id, job_group_code) WHERE (job_group_code IS NOT NULL);


CREATE INDEX idx_job_definition_tenant_id_desc ON batch.job_definition USING btree (tenant_id, id DESC);


CREATE INDEX idx_job_definition_worker_group ON batch.job_definition USING btree (worker_group, enabled);


CREATE INDEX idx_job_dependency_upstream ON batch.job_dependency USING btree (tenant_id, upstream_job_code, downstream_job_code);


CREATE INDEX idx_job_dependency_wait_due ON batch.job_dependency_waiting_launch USING btree (tenant_id, wait_status, updated_at, id);


CREATE INDEX idx_job_dependency_wait_tenant_id ON batch.job_dependency_waiting_launch USING btree (tenant_id, id);


CREATE INDEX idx_job_execution_log_instance_created ON batch.job_execution_log USING btree (job_instance_id, created_at);


CREATE INDEX idx_job_execution_log_partition_created ON batch.job_execution_log USING btree (job_partition_id, created_at);


CREATE INDEX idx_job_execution_log_trace_id ON batch.job_execution_log USING btree (trace_id);


CREATE INDEX idx_job_instance_effective_watermark ON ONLY batch.job_instance USING btree (tenant_id, job_definition_id, finished_at DESC NULLS LAST, id DESC) WHERE (((instance_status)::text = 'SUCCESS'::text) AND (replay_session_id IS NULL) AND (rerun_flag = false) AND (high_water_mark_out IS NOT NULL) AND ((high_water_mark_out)::text <> ''::text));


CREATE INDEX idx_job_instance_failed_partition_monitoring ON ONLY batch.job_instance USING btree (finished_at, tenant_id, id) WHERE ((failed_partition_count > 0) AND ((instance_status)::text = ANY (ARRAY['FAILED'::character varying, 'PARTIAL_FAILED'::character varying])) AND (dry_run = false));


CREATE INDEX idx_job_instance_p_active_tenant_created ON ONLY batch.job_instance USING btree (tenant_id, created_at) WHERE ((instance_status)::text = ANY (ARRAY['CREATED'::character varying, 'WAITING'::character varying, 'READY'::character varying, 'RUNNING'::character varying]));


CREATE INDEX idx_job_instance_p_batch_lookup ON ONLY batch.job_instance USING btree (tenant_id, job_code, biz_date, batch_no);


CREATE INDEX idx_job_instance_p_calendar_bizdate ON ONLY batch.job_instance USING btree (tenant_id, calendar_code, biz_date) WHERE (calendar_code IS NOT NULL);


CREATE INDEX idx_job_instance_p_created_at ON ONLY batch.job_instance USING btree (created_at);


CREATE INDEX idx_job_instance_p_failure_class ON ONLY batch.job_instance USING btree (tenant_id, failure_class, finished_at DESC) WHERE (failure_class IS NOT NULL);


CREATE INDEX idx_job_instance_p_finished_at ON ONLY batch.job_instance USING btree (finished_at) WHERE (finished_at IS NOT NULL);


CREATE INDEX idx_job_instance_p_job_code ON ONLY batch.job_instance USING btree (tenant_id, job_code, biz_date);


CREATE INDEX idx_job_instance_p_job_status ON ONLY batch.job_instance USING btree (tenant_id, job_code, instance_status);


CREATE INDEX idx_job_instance_p_parent_instance_tenant ON ONLY batch.job_instance USING btree (parent_instance_id, tenant_id) WHERE (parent_instance_id IS NOT NULL);


CREATE INDEX idx_job_instance_p_related_file ON ONLY batch.job_instance USING btree (tenant_id, related_file_id);


CREATE INDEX idx_job_instance_p_sla_tracking ON ONLY batch.job_instance USING btree (instance_status, deadline_at, sla_alerted_at);


CREATE INDEX idx_job_instance_p_status ON ONLY batch.job_instance USING btree (tenant_id, instance_status, biz_date);


CREATE INDEX idx_job_instance_p_tenant_bizdate_status ON ONLY batch.job_instance USING btree (tenant_id, biz_date DESC, instance_status);


CREATE INDEX idx_job_instance_p_tenant_status_started ON ONLY batch.job_instance USING btree (tenant_id, instance_status, started_at DESC);


CREATE INDEX idx_job_instance_p_trace_id ON ONLY batch.job_instance USING btree (trace_id);


CREATE INDEX idx_job_instance_start_grace_pending ON ONLY batch.job_instance USING btree (tenant_id, job_definition_id, biz_date, id) WHERE ((dry_run = false) AND (execution_started_at IS NULL) AND (finished_at IS NULL) AND ((instance_status)::text = ANY (ARRAY['CREATED'::character varying, 'WAITING'::character varying, 'READY'::character varying, 'RUNNING'::character varying])));


CREATE INDEX idx_job_instance_trigger_request_id ON ONLY batch.job_instance USING btree (trigger_request_id, tenant_id) WHERE (trigger_request_id IS NOT NULL);


CREATE INDEX idx_job_partition_business_key ON batch.job_partition USING btree (business_key);


CREATE INDEX idx_job_partition_instance_status ON batch.job_partition USING btree (tenant_id, job_instance_id, partition_status);


CREATE INDEX idx_job_partition_lease_expire_at ON batch.job_partition USING btree (lease_expire_at);


CREATE INDEX idx_job_partition_source_file_tenant ON batch.job_partition USING btree (tenant_id, source_file_id);


CREATE INDEX idx_job_partition_status_worker_group ON batch.job_partition USING btree (partition_status, worker_group);


CREATE INDEX idx_job_partition_tenant_instance_date ON batch.job_partition USING btree (tenant_id, job_instance_id, job_instance_biz_date);


CREATE INDEX idx_job_partition_waiting_scan_mode ON batch.job_partition USING btree (partition_status, dry_run, id);


COMMENT ON INDEX batch.idx_job_partition_waiting_scan_mode IS '等待分片按状态、演练模式及 ID 范围轮转扫描。';


CREATE INDEX idx_job_push_in_flight ON batch.console_push_job_delivery USING btree (tenant_id, delivery_claim_until, id) WHERE ((delivery_status)::text = 'IN_FLIGHT'::text);


CREATE INDEX idx_job_push_pending ON batch.console_push_job_delivery USING btree (tenant_id, next_retry_at, id) WHERE (((record_type)::text = 'DEVICE'::text) AND ((delivery_status)::text = 'PENDING'::text));


CREATE INDEX idx_job_step_instance_lookup ON batch.job_step_instance USING btree (tenant_id, job_instance_id, step_code, step_status);


CREATE INDEX idx_job_step_instance_partition_id ON batch.job_step_instance USING btree (job_partition_id) WHERE (job_partition_id IS NOT NULL);


CREATE INDEX idx_job_step_instance_task_id ON batch.job_step_instance USING btree (job_task_id);


CREATE INDEX idx_job_task_instance ON batch.job_task USING btree (job_instance_id, created_at);


CREATE INDEX idx_job_task_partition_tenant_instance ON batch.job_task USING btree (tenant_id, job_partition_id, job_instance_id) WHERE (job_partition_id IS NOT NULL);


CREATE INDEX idx_job_task_priority ON batch.job_task USING btree (tenant_id, task_status, priority DESC, id);


CREATE INDEX idx_job_task_status_worker ON batch.job_task USING btree (task_status, assigned_worker_code);


CREATE INDEX idx_job_task_tenant_instance_date ON batch.job_task USING btree (tenant_id, job_instance_id, job_instance_biz_date);


CREATE INDEX idx_notification_channel_active ON batch.notification_channel USING btree (tenant_id, channel_code) WHERE (is_deleted = false);


CREATE INDEX idx_notification_delivery_log_tenant ON batch.notification_delivery_log USING btree (tenant_id, created_at DESC);


CREATE INDEX idx_notification_terminal_pending ON batch.notification_delivery_log USING btree (next_retry_at, id) WHERE ((terminal_event_key IS NOT NULL) AND (next_retry_at IS NOT NULL));


CREATE INDEX idx_notification_terminal_receipt ON batch.notification_delivery_log USING btree (tenant_id, terminal_event_key) WHERE (terminal_event_key IS NOT NULL);


CREATE INDEX idx_outbox_p_aggregate ON ONLY batch.outbox_event USING btree (aggregate_type, aggregate_id);


CREATE INDEX idx_outbox_p_priority_pending ON ONLY batch.outbox_event USING btree (publish_status, next_publish_at NULLS FIRST, priority DESC, id) WHERE ((publish_status)::text = ANY (ARRAY['NEW'::character varying, 'FAILED'::character varying]));


CREATE INDEX idx_outbox_p_publish_status ON ONLY batch.outbox_event USING btree (publish_status, next_publish_at);


CREATE INDEX idx_outbox_p_tenant_status_scheduled ON ONLY batch.outbox_event USING btree (tenant_id, publish_status, next_publish_at);


CREATE INDEX idx_pipeline_instance_file_id ON batch.pipeline_instance USING btree (file_id);


CREATE INDEX idx_pipeline_instance_trace_id ON batch.pipeline_instance USING btree (trace_id);


CREATE INDEX idx_pipeline_instance_type_status ON batch.pipeline_instance USING btree (tenant_id, pipeline_type, run_status);


CREATE INDEX idx_pipeline_progress_completed_at ON batch.pipeline_progress USING btree (completed_at) WHERE (completed = true);


CREATE INDEX idx_pipeline_progress_tenant_instance ON batch.pipeline_progress USING btree (tenant_id, pipeline_instance_id);


CREATE INDEX idx_pipeline_step_run_status_started ON batch.pipeline_step_run USING btree (step_status, started_at);


CREATE INDEX idx_quota_runtime_state_expire ON batch.quota_runtime_state USING btree (window_expires_at);


CREATE INDEX idx_readiness_wait_due ON batch.readiness_wait USING btree (wait_status, deadline);


CREATE INDEX idx_readiness_wait_probe_rotation ON batch.readiness_wait USING btree (updated_at, deadline, id) WHERE ((wait_status)::text = 'PENDING'::text);


CREATE INDEX idx_readiness_wait_settlement ON batch.readiness_wait USING btree (tenant_id, calendar_code, biz_date, wait_status);


CREATE INDEX idx_readiness_wait_tenant_id ON batch.readiness_wait USING btree (tenant_id, id);


CREATE INDEX idx_replay_entry_rerun_instance ON batch.batch_day_replay_entry USING btree (rerun_instance_id) WHERE (rerun_instance_id IS NOT NULL);


CREATE INDEX idx_replay_entry_session_status ON batch.batch_day_replay_entry USING btree (session_id, status);


CREATE INDEX idx_replay_operation_preview_expiry ON batch.replay_operation_preview_token USING btree (expires_at);


COMMENT ON INDEX batch.idx_replay_operation_preview_expiry IS '支持有界清理已过期的一次性重放预览凭证。';


CREATE INDEX idx_replay_operation_tenant_source_workflow ON batch.replay_operation USING btree (tenant_id, source_workflow_run_id, created_at DESC) WHERE (source_workflow_run_id IS NOT NULL);


COMMENT ON INDEX batch.idx_replay_operation_tenant_source_workflow IS '支持按租户及来源 WorkflowRun 查询重放操作。';


CREATE INDEX idx_replay_operation_tenant_status_created ON batch.replay_operation USING btree (tenant_id, status, created_at DESC);


COMMENT ON INDEX batch.idx_replay_operation_tenant_status_created IS '支持按租户查询重放操作状态列表，并按创建时间排序。';


CREATE INDEX idx_replay_preview_token_consumed_at ON batch.batch_day_replay_preview_token USING btree (consumed_at) WHERE (consumed_at IS NOT NULL);


CREATE INDEX idx_replay_preview_token_expires_at ON batch.batch_day_replay_preview_token USING btree (expires_at);


CREATE INDEX idx_replay_session_operation ON batch.batch_day_replay_session USING btree (tenant_id, replay_operation_id, biz_date) WHERE (replay_operation_id IS NOT NULL);


COMMENT ON INDEX batch.idx_replay_session_operation IS '支持按租户查询父重放操作下的日期会话列表。';


CREATE INDEX idx_replay_session_tenant_bizdate ON batch.batch_day_replay_session USING btree (tenant_id, calendar_code, biz_date, created_at DESC);


CREATE INDEX idx_replay_session_tenant_status ON batch.batch_day_replay_session USING btree (tenant_id, status, created_at DESC);


CREATE INDEX idx_resource_queue_resource_tag ON batch.resource_queue USING btree (resource_tag);


CREATE INDEX idx_resource_queue_type_enabled ON batch.resource_queue USING btree (queue_type, enabled);


CREATE INDEX idx_resource_queue_worker_group ON batch.resource_queue USING btree (worker_group);


CREATE INDEX idx_resource_tag_tenant_key ON batch.resource_tag USING btree (tenant_id, tag_key);


CREATE INDEX idx_resource_tag_tenant_type ON batch.resource_tag USING btree (tenant_id, resource_type);


CREATE INDEX idx_result_version_archived_cleanup ON batch.result_version USING btree (updated_at, id) WHERE ((status)::text = 'ARCHIVED'::text);


COMMENT ON INDEX batch.idx_result_version_archived_cleanup IS '支持分批清理 batch 热表中的 ARCHIVED result_version 记录；归档镜像表保留独立数据';


CREATE INDEX idx_result_version_business_status ON batch.result_version USING btree (tenant_id, business_key, status);


CREATE INDEX idx_result_version_dry_run_retention ON batch.result_version USING btree (generated_at, id) WHERE ((status)::text = 'DRY_RUN'::text);


COMMENT ON INDEX batch.idx_result_version_dry_run_retention IS '支持按独立保留期批量归档 DRY_RUN 结果。';


CREATE INDEX idx_result_version_job_instance ON batch.result_version USING btree (tenant_id, job_instance_id);


CREATE INDEX idx_result_version_status_generated ON batch.result_version USING btree (tenant_id, status, generated_at DESC);


CREATE INDEX idx_result_version_tenant_business_version ON batch.result_version USING btree (tenant_id, business_key, version_no DESC);


COMMENT ON INDEX batch.idx_result_version_tenant_business_version IS '支撑 readiness 禁止旧 EFFECTIVE 在更新版本 PENDING/FAILED 时继续被消费';


CREATE INDEX idx_retry_schedule_next_retry ON batch.retry_schedule USING btree (retry_status, next_retry_at);


CREATE INDEX idx_scheduled_occurrence_coverage_calendar ON batch.scheduled_occurrence_coverage USING btree (tenant_id, calendar_code, coverage_through_at);


CREATE INDEX idx_scheduled_occurrence_day_status ON batch.scheduled_occurrence USING btree (tenant_id, calendar_code, biz_date, occurrence_status);


CREATE INDEX idx_scheduled_occurrence_fire_lookup ON batch.scheduled_occurrence_fire USING btree (tenant_id, workflow_definition_id, scheduled_fire_time, actual_fire_time, id);


CREATE INDEX idx_scheduled_occurrence_readiness_deadline ON batch.scheduled_occurrence USING btree (readiness_deadline) WHERE ((occurrence_status)::text = 'WAITING_READINESS'::text);


CREATE INDEX idx_scheduled_occurrence_supersession_history_identity ON batch.scheduled_occurrence_supersession_history USING btree (tenant_id, workflow_definition_id, scheduled_fire_time, changed_at);


CREATE INDEX idx_scheduled_occurrence_workflow_version ON batch.scheduled_occurrence USING btree (tenant_id, workflow_definition_id, schedule_version, scheduled_fire_time);


CREATE INDEX idx_secret_version_tenant_status ON batch.secret_version USING btree (tenant_id, secret_status, created_at DESC);


CREATE INDEX idx_stateful_backend_cutover_history_feature ON batch.stateful_backend_cutover_history USING btree (feature_key, changed_at DESC);


CREATE INDEX idx_step_registry_module ON batch.step_registry USING btree (module);


CREATE INDEX idx_tenant_quota_enabled ON batch.tenant_quota_policy USING btree (tenant_id, enabled);


CREATE INDEX idx_tenant_quota_tenant_id_desc ON batch.tenant_quota_policy USING btree (tenant_id, id DESC);


CREATE INDEX idx_tenant_scheduler_snapshot_tenant_time ON batch.tenant_scheduler_snapshot USING btree (tenant_id, snapshot_at DESC);


CREATE INDEX idx_terminal_notification_receipt_archive_candidate ON batch.workflow_terminal_notification_receipt USING btree (tenant_id, created_at, event_key);


COMMENT ON INDEX batch.idx_terminal_notification_receipt_archive_candidate IS '按租户与时间顺序有界选择待归档通知族，避免逐批对租户全部回执重新排序。';


CREATE INDEX idx_trigger_launch_recovery_due ON batch.trigger_launch_recovery USING btree (recovery_status, next_attempt_at);


CREATE INDEX idx_trigger_misfire_pending_catch_up_request ON batch.trigger_misfire_pending USING btree (tenant_id, catch_up_request_id) WHERE (catch_up_request_id IS NOT NULL);


COMMENT ON INDEX batch.idx_trigger_misfire_pending_catch_up_request IS 'Console 审批前按租户和请求 ID 定位原 misfire 义务，再发布恢复 Outbox。';


CREATE INDEX idx_trigger_misfire_pending_status ON batch.trigger_misfire_pending USING btree (status, expires_at) WHERE ((status)::text = 'PENDING'::text);


CREATE INDEX idx_trigger_misfire_pending_tenant_workflow ON batch.trigger_misfire_pending USING btree (tenant_id, workflow_definition_id, detected_at DESC);


CREATE INDEX idx_trigger_outbox_event_pending ON batch.trigger_outbox_event USING btree (publish_status, next_publish_at) WHERE ((publish_status)::text = ANY (ARRAY['NEW'::character varying, 'FAILED'::character varying]));


CREATE INDEX idx_trigger_outbox_event_pending_order ON batch.trigger_outbox_event USING btree (next_publish_at, created_at, id) WHERE ((publish_status)::text = ANY (ARRAY['NEW'::character varying, 'FAILED'::character varying]));


CREATE INDEX idx_trigger_outbox_event_publishing_stale ON batch.trigger_outbox_event USING btree (updated_at) WHERE ((publish_status)::text = 'PUBLISHING'::text);


CREATE INDEX idx_trigger_request_created_at ON batch.trigger_request USING btree (created_at);


CREATE INDEX idx_trigger_request_job_biz_date ON batch.trigger_request USING btree (tenant_id, job_code, biz_date);


CREATE INDEX idx_trigger_request_parent_workflow ON batch.trigger_request USING btree (tenant_id, parent_workflow_run_id) WHERE (parent_workflow_run_id IS NOT NULL);


CREATE INDEX idx_trigger_request_stale_accepted ON batch.trigger_request USING btree (created_at, id) INCLUDE (tenant_id, request_id) WHERE (((request_status)::text = 'ACCEPTED'::text) AND (related_job_instance_id IS NULL));


CREATE INDEX idx_trigger_request_tenant_dedup ON batch.trigger_request USING btree (tenant_id, dedup_key);


CREATE INDEX idx_webhook_delivery_log_legacy_retry ON batch.webhook_delivery_log USING btree (next_retry_at, id) WHERE ((terminal_event_key IS NULL) AND (next_retry_at IS NOT NULL));


CREATE INDEX idx_webhook_delivery_log_sub ON batch.webhook_delivery_log USING btree (subscription_id);


CREATE INDEX idx_webhook_delivery_log_tenant ON batch.webhook_delivery_log USING btree (tenant_id, created_at DESC);


CREATE INDEX idx_webhook_subscription_active ON batch.webhook_subscription USING btree (tenant_id, name) WHERE (is_deleted = false);


CREATE INDEX idx_webhook_terminal_pending ON batch.webhook_delivery_log USING btree (next_retry_at, id) WHERE ((terminal_event_key IS NOT NULL) AND (next_retry_at IS NOT NULL));


CREATE INDEX idx_webhook_terminal_receipt ON batch.webhook_delivery_log USING btree (tenant_id, terminal_event_key) WHERE (terminal_event_key IS NOT NULL);


CREATE INDEX idx_worker_registry_group_status ON batch.worker_registry USING btree (worker_group, status);


CREATE INDEX idx_worker_registry_heartbeat_at ON batch.worker_registry USING btree (heartbeat_at);


CREATE INDEX idx_worker_registry_load ON batch.worker_registry USING btree (tenant_id, worker_group, status, current_load);


CREATE INDEX idx_worker_registry_pool_status_load ON batch.worker_registry USING btree (tenant_id, worker_pool_code, status, current_load, heartbeat_at DESC);


CREATE INDEX idx_worker_report_outbox_poll ON batch.worker_report_outbox USING btree (publish_status, next_attempt_at);


CREATE INDEX idx_workflow_definition_enabled ON batch.workflow_definition USING btree (tenant_id, enabled);


CREATE INDEX idx_workflow_definition_scheduled ON batch.workflow_definition USING btree (tenant_id, schedule_type, trigger_mode, workflow_code) WHERE ((enabled = true) AND ((schedule_type)::text = ANY (ARRAY['CRON'::character varying, 'FIXED_RATE'::character varying])));


CREATE INDEX idx_workflow_definition_tenant_code_version ON batch.workflow_definition USING btree (tenant_id, workflow_code, version DESC, id DESC);


CREATE INDEX idx_workflow_definition_version_def_ver_desc ON batch.workflow_definition_version USING btree (workflow_definition_id, version DESC);


CREATE INDEX idx_workflow_edge_from ON batch.workflow_edge USING btree (workflow_definition_id, from_node_code);


CREATE INDEX idx_workflow_edge_tenant_def ON batch.workflow_edge USING btree (tenant_id, workflow_definition_id);


CREATE INDEX idx_workflow_edge_to ON batch.workflow_edge USING btree (workflow_definition_id, to_node_code);


CREATE INDEX idx_workflow_launch_wait_due ON batch.workflow_launch_wait USING btree (tenant_id, next_attempt_at, created_at, id) WHERE ((wait_status)::text = 'WAITING'::text);


CREATE INDEX idx_workflow_launch_wait_tenant_definition ON batch.workflow_launch_wait USING btree (tenant_id, workflow_definition_id);


CREATE INDEX idx_workflow_launch_wait_tenant_id ON batch.workflow_launch_wait USING btree (tenant_id, id);


CREATE INDEX idx_workflow_node_run_run_id_node_code ON batch.workflow_node_run USING btree (workflow_run_id, node_code, run_seq DESC);


CREATE INDEX idx_workflow_node_run_sensor_next_probe ON batch.workflow_node_run USING btree (sensor_next_probe_at) WHERE (((node_type)::text = 'WAIT'::text) AND ((node_status)::text = 'RUNNING'::text));


CREATE INDEX idx_workflow_node_run_status_started ON batch.workflow_node_run USING btree (node_status, started_at);


CREATE INDEX idx_workflow_node_tenant_code ON batch.workflow_node USING btree (tenant_id, node_code);


CREATE INDEX idx_workflow_node_type_enabled ON batch.workflow_node USING btree (node_type, enabled);


CREATE INDEX idx_workflow_run_active_lookup ON batch.workflow_run USING btree (tenant_id, workflow_definition_id, biz_date, dry_run) WHERE ((run_status)::text = ANY (ARRAY['CREATED'::character varying, 'RUNNING'::character varying]));


CREATE INDEX idx_workflow_run_def_bizdate ON batch.workflow_run USING btree (tenant_id, workflow_definition_id, biz_date);


CREATE INDEX idx_workflow_run_job_instance ON batch.workflow_run USING btree (related_job_instance_id);


CREATE INDEX idx_workflow_run_job_version_lookup ON batch.workflow_run USING btree (tenant_id, created_at DESC, id DESC) WHERE (execution_snapshot IS NOT NULL);


COMMENT ON INDEX batch.idx_workflow_run_job_version_lookup IS '按租户查询单作业及组合 WorkflowRun 中 JobDefinition 快照，并按时间倒序读取。';


CREATE INDEX idx_workflow_run_status ON batch.workflow_run USING btree (tenant_id, run_status, started_at);


CREATE INDEX idx_workflow_run_tenant_status_bizdate ON batch.workflow_run USING btree (tenant_id, run_status, biz_date DESC);


CREATE INDEX idx_workflow_run_trace_id ON batch.workflow_run USING btree (trace_id);


CREATE UNIQUE INDEX uk_approval_command_tenant_source_idempotency ON batch.approval_command USING btree (tenant_id, source_idempotency_key) WHERE (source_idempotency_key IS NOT NULL);


COMMENT ON INDEX batch.uk_approval_command_tenant_source_idempotency IS '每个租户范围的来源幂等键仅对应一条持久化审批命令。';


CREATE UNIQUE INDEX uk_compensation_command_running_target ON batch.compensation_command USING btree (tenant_id, compensation_type, target_id) WHERE (((command_status)::text = 'RUNNING'::text) AND (target_id IS NOT NULL));


CREATE UNIQUE INDEX uk_compensation_command_tenant_approval ON batch.compensation_command USING btree (tenant_id, approval_id) WHERE (approval_id IS NOT NULL);


CREATE UNIQUE INDEX uk_config_approval_pending_release ON batch.config_approval USING btree (tenant_id, release_id) WHERE ((approval_status)::text = 'PENDING'::text);


CREATE UNIQUE INDEX uk_config_sync_import_request ON batch.config_sync_log USING btree (tenant_id, ((detail_json ->> 'requestKeyHash'::text))) WHERE (((sync_direction)::text = 'IMPORT'::text) AND (detail_json ? 'requestKeyHash'::text));


COMMENT ON INDEX batch.uk_config_sync_import_request IS '配置导入请求身份；目标提交后审计失败也不能用同一 HTTP 请求重复导入。';


CREATE UNIQUE INDEX uk_config_sync_log_tenant_id ON batch.config_sync_log USING btree (tenant_id, id);


CREATE UNIQUE INDEX uk_console_ai_turn_client_id ON batch.console_ai_turn USING btree (tenant_id, client_turn_id) WHERE (client_turn_id IS NOT NULL);


CREATE UNIQUE INDEX uk_console_user_account_tenant_id_id ON batch.console_user_account USING btree (tenant_id, id);


CREATE UNIQUE INDEX uk_console_user_account_username_ci ON batch.console_user_account USING btree (lower((username)::text));


CREATE UNIQUE INDEX uk_disaster_active ON batch.disaster_day_override USING btree (tenant_id, calendar_code, biz_date);


CREATE UNIQUE INDEX uk_file_audit_dispatch_completion_request ON batch.file_audit_log USING btree (tenant_id, file_id, evidence_ref, ((detail_summary ->> 'channelCode'::text))) WHERE (((operation_type)::text = 'DISPATCH_COMPLETE'::text) AND (evidence_ref IS NOT NULL) AND ((detail_summary ->> 'channelCode'::text) IS NOT NULL));


CREATE UNIQUE INDEX uk_file_record_no_checksum ON batch.file_record USING btree (tenant_id, storage_path) WHERE (checksum_value IS NULL);


CREATE UNIQUE INDEX uk_file_record_tenant_id ON batch.file_record USING btree (tenant_id, id);


CREATE UNIQUE INDEX uk_file_record_with_checksum ON batch.file_record USING btree (tenant_id, checksum_value, storage_path) WHERE (checksum_value IS NOT NULL);


CREATE UNIQUE INDEX uk_import_ingress_ownership_binding_enabled ON batch.import_ingress_ownership_binding USING btree (scope_id, binding_type, tenant_id, object_prefix) WHERE enabled;


CREATE UNIQUE INDEX uk_job_definition_tenant_id ON batch.job_definition USING btree (tenant_id, id);


CREATE UNIQUE INDEX uk_job_instance_tenant_id_biz_date ON ONLY batch.job_instance USING btree (tenant_id, id, biz_date);


CREATE UNIQUE INDEX uk_job_partition_idempotency_key ON batch.job_partition USING btree (tenant_id, idempotency_key) WHERE (idempotency_key IS NOT NULL);


CREATE UNIQUE INDEX uk_job_partition_tenant_id_instance ON batch.job_partition USING btree (tenant_id, id, job_instance_id);


CREATE UNIQUE INDEX uk_job_task_no_partition_seq ON batch.job_task USING btree (job_instance_id, task_seq) WHERE (job_partition_id IS NULL);


CREATE UNIQUE INDEX uk_job_task_partition_seq ON batch.job_task USING btree (job_partition_id, task_seq) WHERE (job_partition_id IS NOT NULL);


CREATE UNIQUE INDEX uk_job_task_step_ownership ON batch.job_task USING btree (tenant_id, id, job_instance_id, job_partition_ref_key);


CREATE UNIQUE INDEX uk_notification_terminal_recipient ON batch.notification_delivery_log USING btree (tenant_id, rule_id, terminal_event_key) WHERE (terminal_event_key IS NOT NULL);


CREATE UNIQUE INDEX uk_pipeline_instance_job_instance ON batch.pipeline_instance USING btree (related_job_instance_id) WHERE (related_job_instance_id IS NOT NULL);


CREATE UNIQUE INDEX uk_pipeline_instance_tenant_id ON batch.pipeline_instance USING btree (tenant_id, id);


CREATE UNIQUE INDEX uk_replay_entry_session_result_version ON batch.batch_day_replay_entry USING btree (session_id, tenant_id, result_version_id) WHERE (result_version_id IS NOT NULL);


COMMENT ON INDEX batch.uk_replay_entry_session_result_version IS '同一 replay session 内同一个 result_version_id 只能 promote 一次;允许同 jobCode 多版本';


CREATE UNIQUE INDEX uk_replay_entry_session_source_instance ON batch.batch_day_replay_entry USING btree (session_id, tenant_id, source_instance_id) WHERE ((source_instance_id IS NOT NULL) AND (result_version_id IS NULL));


COMMENT ON INDEX batch.uk_replay_entry_session_source_instance IS '同一 replay session 内同一个 source_instance_id 只能物化一次;OUTPUTS_ONLY 按 result_version_id 去重,允许同 source instance 多版本';


CREATE UNIQUE INDEX uk_replay_session_active ON batch.batch_day_replay_session USING btree (tenant_id, calendar_code, biz_date) WHERE ((status)::text = ANY (ARRAY['PENDING_APPROVAL'::character varying, 'RUNNING'::character varying]));


CREATE UNIQUE INDEX uk_result_version_business_version ON batch.result_version USING btree (tenant_id, business_key, version_no);


CREATE UNIQUE INDEX uk_result_version_effective ON batch.result_version USING btree (tenant_id, business_key) WHERE ((status)::text = 'EFFECTIVE'::text);


CREATE UNIQUE INDEX uk_secret_version_current ON batch.secret_version USING btree (tenant_id, secret_ref) WHERE (current_version = true);


CREATE UNIQUE INDEX uk_stateful_backend_cutover_token ON batch.stateful_backend_cutover_history USING btree (feature_key, cutover_id) WHERE (cutover_id IS NOT NULL);


CREATE UNIQUE INDEX uk_trigger_misfire_pending_workflow_fire ON batch.trigger_misfire_pending USING btree (tenant_id, workflow_definition_id, scheduled_fire_time);


CREATE UNIQUE INDEX uk_trigger_request_batch_day_catchup_root ON batch.trigger_request USING btree (tenant_id, dedup_key) WHERE (((trigger_type)::text = 'CATCH_UP'::text) AND (parent_workflow_run_id IS NULL) AND ("left"((dedup_key)::text, (length((tenant_id)::text) + length(':batch-day-catchup:'::text))) = ((tenant_id)::text || ':batch-day-catchup:'::text)));


COMMENT ON INDEX batch.uk_trigger_request_batch_day_catchup_root IS '同租户的批日补跑根请求按冻结业务键唯一；子请求不受此约束。';


CREATE UNIQUE INDEX uk_trigger_request_external_event_delivery ON batch.trigger_request USING btree (tenant_id, workflow_definition_id, external_event_source, external_event_id) WHERE (external_event_source IS NOT NULL);


CREATE UNIQUE INDEX uk_trigger_request_tenant_id ON batch.trigger_request USING btree (tenant_id, id);


CREATE UNIQUE INDEX uk_webhook_terminal_recipient ON batch.webhook_delivery_log USING btree (tenant_id, subscription_id, terminal_event_key) WHERE (terminal_event_key IS NOT NULL);


CREATE UNIQUE INDEX uk_workflow_definition_tenant_id ON batch.workflow_definition USING btree (tenant_id, id);


CREATE UNIQUE INDEX uk_workflow_definition_tenant_id_calendar ON batch.workflow_definition USING btree (tenant_id, id, calendar_code);


CREATE UNIQUE INDEX uk_workflow_node_run_job_instance ON batch.workflow_node_run USING btree (job_instance_id) WHERE (job_instance_id IS NOT NULL);


CREATE UNIQUE INDEX uk_workflow_run_active_slot ON batch.workflow_run USING btree (tenant_id, workflow_definition_id, biz_date, dry_run, active_slot) WHERE ((run_status)::text = ANY (ARRAY['CREATED'::character varying, 'RUNNING'::character varying]));


CREATE UNIQUE INDEX uk_workflow_run_trigger_request ON batch.workflow_run USING btree (tenant_id, trigger_request_id) WHERE (trigger_request_id IS NOT NULL);


CREATE UNIQUE INDEX uk_workflow_single_job_definition ON batch.workflow_definition USING btree (tenant_id, single_job_definition_id) WHERE (single_job_definition_id IS NOT NULL);


CREATE UNIQUE INDEX uq_file_dispatch_record_tenant_file_channel ON batch.file_dispatch_record USING btree (tenant_id, file_id, channel_code);


CREATE UNIQUE INDEX uq_replay_operation_tenant_target_request ON batch.replay_operation USING btree (tenant_id, target_request_id) WHERE (target_request_id IS NOT NULL);


COMMENT ON INDEX batch.uq_replay_operation_tenant_target_request IS '每个租户范围的目标请求至多对应一个重放操作；支持异步关联 Workflow 根运行时保持幂等。';


CREATE UNIQUE INDEX uq_terminal_notification_retry_audit_request ON batch.console_operation_audit USING btree (tenant_id, aggregate_id, request_id) WHERE (((aggregate_type)::text = 'NOTIFICATION_DELIVERY'::text) AND ((action)::text = 'NOTIFICATION_DELIVERY_RETRY'::text));


CREATE INDEX idx_qrtz_ft_inst_job_req_rcvry ON quartz.qrtz_fired_triggers USING btree (sched_name, instance_name, requests_recovery);


CREATE INDEX idx_qrtz_ft_j_g ON quartz.qrtz_fired_triggers USING btree (sched_name, job_name, job_group);


CREATE INDEX idx_qrtz_ft_jg ON quartz.qrtz_fired_triggers USING btree (sched_name, job_group);


CREATE INDEX idx_qrtz_ft_t_g ON quartz.qrtz_fired_triggers USING btree (sched_name, trigger_name, trigger_group);


CREATE INDEX idx_qrtz_ft_tg ON quartz.qrtz_fired_triggers USING btree (sched_name, trigger_group);


CREATE INDEX idx_qrtz_ft_trig_inst_name ON quartz.qrtz_fired_triggers USING btree (sched_name, instance_name);


CREATE INDEX idx_qrtz_j_grp ON quartz.qrtz_job_details USING btree (sched_name, job_group);


CREATE INDEX idx_qrtz_j_req_recovery ON quartz.qrtz_job_details USING btree (sched_name, requests_recovery);


CREATE INDEX idx_qrtz_t_c ON quartz.qrtz_triggers USING btree (sched_name, calendar_name);


CREATE INDEX idx_qrtz_t_g ON quartz.qrtz_triggers USING btree (sched_name, trigger_group);


CREATE INDEX idx_qrtz_t_j ON quartz.qrtz_triggers USING btree (sched_name, job_name, job_group);


CREATE INDEX idx_qrtz_t_jg ON quartz.qrtz_triggers USING btree (sched_name, job_group);


CREATE INDEX idx_qrtz_t_n_g_state ON quartz.qrtz_triggers USING btree (sched_name, trigger_group, trigger_state);


CREATE INDEX idx_qrtz_t_n_state ON quartz.qrtz_triggers USING btree (sched_name, trigger_name, trigger_group, trigger_state);


CREATE INDEX idx_qrtz_t_next_fire_time ON quartz.qrtz_triggers USING btree (sched_name, next_fire_time);


CREATE INDEX idx_qrtz_t_nft_misfire ON quartz.qrtz_triggers USING btree (sched_name, misfire_instr, next_fire_time);


CREATE INDEX idx_qrtz_t_nft_st ON quartz.qrtz_triggers USING btree (sched_name, trigger_state, next_fire_time);


CREATE INDEX idx_qrtz_t_nft_st_misfire ON quartz.qrtz_triggers USING btree (sched_name, misfire_instr, next_fire_time, trigger_state);


CREATE INDEX idx_qrtz_t_nft_st_misfire_grp ON quartz.qrtz_triggers USING btree (sched_name, misfire_instr, next_fire_time, trigger_group, trigger_state);


CREATE INDEX idx_qrtz_t_state ON quartz.qrtz_triggers USING btree (sched_name, trigger_state);


CREATE TRIGGER file_channel_resource_tag_cleanup AFTER DELETE OR UPDATE OF tenant_id, channel_code ON batch.file_channel_config FOR EACH ROW EXECUTE FUNCTION batch.cleanup_missing_resource_tag_parent('FILE_CHANNEL', 'channel_code', 'file_channel_config');


CREATE TRIGGER file_channel_resource_tag_lock BEFORE INSERT OR DELETE OR UPDATE OF tenant_id, channel_code ON batch.file_channel_config FOR EACH ROW EXECUTE FUNCTION batch.lock_resource_tag_parent_identity('FILE_CHANNEL', 'channel_code');


CREATE TRIGGER file_template_resource_tag_cleanup AFTER DELETE OR UPDATE OF tenant_id, template_code ON batch.file_template_config FOR EACH ROW EXECUTE FUNCTION batch.cleanup_missing_resource_tag_parent('FILE_TEMPLATE', 'template_code', 'file_template_config');


CREATE TRIGGER file_template_resource_tag_lock BEFORE INSERT OR DELETE OR UPDATE OF tenant_id, template_code ON batch.file_template_config FOR EACH ROW EXECUTE FUNCTION batch.lock_resource_tag_parent_identity('FILE_TEMPLATE', 'template_code');


CREATE TRIGGER job_definition_resource_tag_cleanup AFTER DELETE OR UPDATE OF tenant_id, job_code ON batch.job_definition FOR EACH ROW EXECUTE FUNCTION batch.cleanup_missing_resource_tag_parent('JOB', 'job_code', 'job_definition');


CREATE TRIGGER job_definition_resource_tag_lock BEFORE INSERT OR DELETE OR UPDATE OF tenant_id, job_code ON batch.job_definition FOR EACH ROW EXECUTE FUNCTION batch.lock_resource_tag_parent_identity('JOB', 'job_code');


CREATE TRIGGER resource_tag_validate_target BEFORE INSERT OR UPDATE OF tenant_id, resource_type, resource_code ON batch.resource_tag FOR EACH ROW EXECUTE FUNCTION batch.validate_resource_tag_target();


CREATE TRIGGER trg_batch_window_cache_invalidation AFTER INSERT OR DELETE OR UPDATE ON batch.batch_window FOR EACH ROW EXECUTE FUNCTION batch.record_config_cache_invalidation('batch-window', 'window_code');


CREATE TRIGGER trg_batch_window_tenant_config_revision AFTER INSERT OR DELETE OR UPDATE ON batch.batch_window FOR EACH ROW EXECUTE FUNCTION batch.bump_tenant_configuration_revision();


CREATE TRIGGER trg_business_calendar_cache_invalidation AFTER INSERT OR DELETE OR UPDATE ON batch.business_calendar FOR EACH ROW EXECUTE FUNCTION batch.record_config_cache_invalidation('business-calendar', 'calendar_code');


CREATE TRIGGER trg_business_calendar_tenant_config_revision AFTER INSERT OR DELETE OR UPDATE ON batch.business_calendar FOR EACH ROW EXECUTE FUNCTION batch.bump_tenant_configuration_revision();


CREATE TRIGGER trg_calendar_holiday_tenant_config_revision AFTER INSERT OR DELETE OR UPDATE ON batch.calendar_holiday FOR EACH ROW EXECUTE FUNCTION batch.bump_tenant_configuration_revision();


CREATE TRIGGER trg_dq_check_job_instance_reference BEFORE INSERT OR UPDATE OF tenant_id, job_instance_id ON batch.data_quality_check FOR EACH ROW EXECUTE FUNCTION batch.check_data_quality_check_job_instance_reference();


COMMENT ON TRIGGER trg_dq_check_job_instance_reference ON batch.data_quality_check IS '关联时必须存在唯一的同租户 JobInstance；之后仍允许归档父记录。';


CREATE TRIGGER trg_file_channel_config_tenant_config_revision AFTER INSERT OR DELETE OR UPDATE ON batch.file_channel_config FOR EACH ROW EXECUTE FUNCTION batch.bump_tenant_configuration_revision();


CREATE TRIGGER trg_file_template_config_tenant_config_revision AFTER INSERT OR DELETE OR UPDATE ON batch.file_template_config FOR EACH ROW EXECUTE FUNCTION batch.bump_tenant_configuration_revision();


CREATE TRIGGER trg_guard_calendar_dependency_acyclic BEFORE INSERT OR UPDATE OF tenant_id, upstream_code, downstream_code, enabled ON batch.calendar_dependency FOR EACH ROW EXECUTE FUNCTION batch.guard_calendar_dependency_acyclic();


COMMENT ON TRIGGER trg_guard_calendar_dependency_acyclic ON batch.calendar_dependency IS '并发写入时阻止启用的日历依赖形成环';


CREATE TRIGGER trg_guard_job_dependency_acyclic BEFORE INSERT OR UPDATE OF tenant_id, downstream_job_code, upstream_job_code ON batch.job_dependency FOR EACH ROW EXECUTE FUNCTION batch.guard_job_dependency_acyclic();


COMMENT ON TRIGGER trg_guard_job_dependency_acyclic ON batch.job_dependency IS '在租户级事务 advisory lock 下约束 Job 依赖图更新，保持依赖图无环。';


CREATE TRIGGER trg_job_definition_cache_invalidation AFTER INSERT OR DELETE OR UPDATE ON batch.job_definition FOR EACH ROW EXECUTE FUNCTION batch.record_config_cache_invalidation('job-definition', 'job_code');


CREATE TRIGGER trg_job_definition_tenant_config_revision AFTER INSERT OR DELETE OR UPDATE ON batch.job_definition FOR EACH ROW EXECUTE FUNCTION batch.bump_tenant_configuration_revision();


CREATE TRIGGER trg_job_instance_workflow_node_reference_guard BEFORE DELETE OR UPDATE OF tenant_id, id, biz_date ON batch.job_instance FOR EACH ROW EXECUTE FUNCTION batch.protect_job_instance_workflow_node_reference();


COMMENT ON TRIGGER trg_job_instance_workflow_node_reference_guard ON batch.job_instance IS '当活跃 Workflow 节点仍引用 JobInstance 时，拒绝删除或变更其键；当 WorkflowRun、TriggerRequest 或 DQ 历史记录引用其身份时，也拒绝身份变更。';


CREATE TRIGGER trg_job_monitoring_policy_tenant_config_revision AFTER INSERT OR DELETE OR UPDATE ON batch.job_monitoring_policy FOR EACH ROW EXECUTE FUNCTION batch.bump_tenant_configuration_revision();


CREATE TRIGGER trg_resource_queue_tenant_config_revision AFTER INSERT OR DELETE OR UPDATE ON batch.resource_queue FOR EACH ROW EXECUTE FUNCTION batch.bump_tenant_configuration_revision();


CREATE TRIGGER trg_scheduled_occurrence_supersession_history AFTER UPDATE OF occurrence_status ON batch.scheduled_occurrence FOR EACH ROW EXECUTE FUNCTION batch.record_scheduled_occurrence_supersession();


CREATE TRIGGER trg_sync_legacy_job_dependency AFTER INSERT OR UPDATE OF depends_on_job_code ON batch.job_definition FOR EACH ROW EXECUTE FUNCTION batch.sync_legacy_job_dependency();


CREATE TRIGGER trg_tenant_quota_policy_cache_invalidation AFTER INSERT OR DELETE OR UPDATE ON batch.tenant_quota_policy FOR EACH ROW EXECUTE FUNCTION batch.record_config_cache_invalidation('tenant-quota-policy', '');


CREATE TRIGGER trg_trigger_request_batch_day_admission BEFORE INSERT ON batch.trigger_request FOR EACH ROW EXECUTE FUNCTION batch.guard_trigger_request_batch_day_admission();


CREATE TRIGGER trg_trigger_request_job_instance_reference BEFORE INSERT OR UPDATE OF tenant_id, related_job_instance_id ON batch.trigger_request FOR EACH ROW EXECUTE FUNCTION batch.check_trigger_request_job_instance_reference();


COMMENT ON TRIGGER trg_trigger_request_job_instance_reference ON batch.trigger_request IS 'TriggerRequest 关联历史弱引用时必须存在同租户 JobInstance，同时允许之后归档父记录。';


CREATE TRIGGER trg_trigger_request_parent_workflow_immutable BEFORE UPDATE OF parent_workflow_run_id, parent_node_code ON batch.trigger_request FOR EACH ROW EXECUTE FUNCTION batch.reject_trigger_request_parent_workflow_relink();


COMMENT ON TRIGGER trg_trigger_request_parent_workflow_immutable ON batch.trigger_request IS '拒绝在请求创建后更改或清空历史父 WorkflowRun/节点身份。';


CREATE TRIGGER trg_workflow_definition_cache_invalidation AFTER INSERT OR DELETE OR UPDATE ON batch.workflow_definition FOR EACH ROW EXECUTE FUNCTION batch.record_config_cache_invalidation('workflow-definition', 'workflow_code');


CREATE TRIGGER trg_workflow_definition_tenant_config_revision AFTER INSERT OR DELETE OR UPDATE ON batch.workflow_definition FOR EACH ROW EXECUTE FUNCTION batch.bump_tenant_configuration_revision();


CREATE TRIGGER trg_workflow_edge_tenant_config_revision AFTER INSERT OR DELETE OR UPDATE ON batch.workflow_edge FOR EACH ROW EXECUTE FUNCTION batch.bump_tenant_configuration_revision();


CREATE TRIGGER trg_workflow_node_run_job_instance_tenant_check BEFORE INSERT OR UPDATE OF workflow_run_id, job_instance_id ON batch.workflow_node_run FOR EACH ROW EXECUTE FUNCTION batch.check_workflow_node_run_job_instance_tenant();


CREATE TRIGGER trg_workflow_node_tenant_config_revision AFTER INSERT OR DELETE OR UPDATE ON batch.workflow_node FOR EACH ROW EXECUTE FUNCTION batch.bump_tenant_configuration_revision();


CREATE TRIGGER trg_workflow_run_tenant_check BEFORE INSERT OR UPDATE OF related_job_instance_id, tenant_id ON batch.workflow_run FOR EACH ROW EXECUTE FUNCTION batch.check_workflow_run_tenant_consistency();


CREATE TRIGGER trg_workflow_run_trigger_request_reference_guard BEFORE UPDATE OF id, tenant_id, biz_date ON batch.workflow_run FOR EACH ROW EXECUTE FUNCTION batch.protect_workflow_run_trigger_request_reference();


COMMENT ON TRIGGER trg_workflow_run_trigger_request_reference_guard ON batch.workflow_run IS '保护作为 TriggerRequest 历史父记录的 WorkflowRun 身份，并允许归档时删除弱关联。';


CREATE TRIGGER workflow_definition_resource_tag_cleanup AFTER DELETE OR UPDATE OF tenant_id, workflow_code ON batch.workflow_definition FOR EACH ROW EXECUTE FUNCTION batch.cleanup_missing_resource_tag_parent('WORKFLOW', 'workflow_code', 'workflow_definition');


CREATE TRIGGER workflow_definition_resource_tag_lock BEFORE INSERT OR DELETE OR UPDATE OF tenant_id, workflow_code ON batch.workflow_definition FOR EACH ROW EXECUTE FUNCTION batch.lock_resource_tag_parent_identity('WORKFLOW', 'workflow_code');


ALTER TABLE ONLY archive.console_push_job_delivery_archive
    ADD CONSTRAINT fk_console_push_job_delivery_cold_receipt FOREIGN KEY (tenant_id, terminal_event_key) REFERENCES archive.workflow_terminal_notification_receipt_archive(tenant_id, event_key);


ALTER TABLE ONLY archive.notification_delivery_log_archive
    ADD CONSTRAINT fk_notification_delivery_log_cold_receipt FOREIGN KEY (tenant_id, terminal_event_key) REFERENCES archive.workflow_terminal_notification_receipt_archive(tenant_id, event_key);


ALTER TABLE ONLY archive.webhook_delivery_log_archive
    ADD CONSTRAINT fk_webhook_delivery_log_cold_receipt FOREIGN KEY (tenant_id, terminal_event_key) REFERENCES archive.workflow_terminal_notification_receipt_archive(tenant_id, event_key);


ALTER TABLE ONLY batch.asset_partition
    ADD CONSTRAINT asset_partition_asset_id_fkey FOREIGN KEY (asset_id) REFERENCES batch.data_asset(id);


ALTER TABLE ONLY batch.asset_partition
    ADD CONSTRAINT asset_partition_result_version_id_fkey FOREIGN KEY (result_version_id) REFERENCES batch.result_version(id);


ALTER TABLE ONLY batch.file_audit_log
    ADD CONSTRAINT file_audit_log_file_id_fkey FOREIGN KEY (file_id) REFERENCES batch.file_record(id) ON DELETE SET NULL;


ALTER TABLE ONLY batch.file_dispatch_record
    ADD CONSTRAINT file_dispatch_record_file_id_fkey FOREIGN KEY (file_id) REFERENCES batch.file_record(id) ON DELETE CASCADE;


ALTER TABLE ONLY batch.file_dispatch_record
    ADD CONSTRAINT file_dispatch_record_pipeline_instance_id_fkey FOREIGN KEY (pipeline_instance_id) REFERENCES batch.pipeline_instance(id);


ALTER TABLE ONLY batch.alert_notification_receipt
    ADD CONSTRAINT fk_alert_notification_receipt_event FOREIGN KEY (tenant_id, dedup_fingerprint) REFERENCES batch.alert_event(tenant_id, dedup_fingerprint) ON DELETE CASCADE;


ALTER TABLE ONLY batch.asset_freshness_policy
    ADD CONSTRAINT fk_asset_freshness_policy_job_definition_tenant FOREIGN KEY (tenant_id, asset_code) REFERENCES batch.job_definition(tenant_id, job_code) ON DELETE CASCADE;


COMMENT ON CONSTRAINT fk_asset_freshness_policy_job_definition_tenant ON batch.asset_freshness_policy IS '租户范围内的所属 Job；监控策略依赖该 Job 配置。';


ALTER TABLE ONLY batch.batch_day_catchup_control
    ADD CONSTRAINT fk_batch_day_catchup_control_business_calendar FOREIGN KEY (tenant_id, calendar_code) REFERENCES batch.business_calendar(tenant_id, calendar_code);


ALTER TABLE ONLY batch.batch_day_replay_entry
    ADD CONSTRAINT fk_batch_day_replay_entry_session_tenant FOREIGN KEY (tenant_id, session_id) REFERENCES batch.batch_day_replay_session(tenant_id, id);


ALTER TABLE ONLY batch.batch_day_waiting_launch
    ADD CONSTRAINT fk_batch_day_wait_launch_trigger_request FOREIGN KEY (tenant_id, request_id) REFERENCES batch.trigger_request(tenant_id, request_id);


ALTER TABLE ONLY batch.business_calendar
    ADD CONSTRAINT fk_business_calendar_calendar_group_tenant FOREIGN KEY (tenant_id, group_code) REFERENCES batch.calendar_group(tenant_id, group_code);


COMMENT ON CONSTRAINT fk_business_calendar_calendar_group_tenant ON batch.business_calendar IS '日历加入的分组必须存在且属于同一租户';


ALTER TABLE ONLY batch.calendar_dependency
    ADD CONSTRAINT fk_calendar_dependency_downstream_calendar FOREIGN KEY (tenant_id, downstream_code) REFERENCES batch.business_calendar(tenant_id, calendar_code);


ALTER TABLE ONLY batch.calendar_dependency
    ADD CONSTRAINT fk_calendar_dependency_upstream_calendar FOREIGN KEY (tenant_id, upstream_code) REFERENCES batch.business_calendar(tenant_id, calendar_code);


ALTER TABLE ONLY batch.calendar_holiday
    ADD CONSTRAINT fk_calendar_holiday_business_calendar_tenant FOREIGN KEY (tenant_id, calendar_id) REFERENCES batch.business_calendar(tenant_id, id);


COMMENT ON CONSTRAINT fk_calendar_holiday_business_calendar_tenant ON batch.calendar_holiday IS '节假日只能引用同租户业务日历，并阻止日历在节假日记录保留期间被删除';


ALTER TABLE ONLY batch.calendar_holiday
    ADD CONSTRAINT fk_calendar_holiday_calendar_group_tenant FOREIGN KEY (tenant_id, group_code) REFERENCES batch.calendar_group(tenant_id, group_code);


COMMENT ON CONSTRAINT fk_calendar_holiday_calendar_group_tenant ON batch.calendar_holiday IS 'GROUP 假日引用的分组必须存在且属于同一租户';


ALTER TABLE ONLY batch.calendar_holiday
    ADD CONSTRAINT fk_calendar_holiday_group_representative_calendar FOREIGN KEY (tenant_id, group_code, calendar_id) REFERENCES batch.business_calendar(tenant_id, group_code, id);


COMMENT ON CONSTRAINT fk_calendar_holiday_group_representative_calendar ON batch.calendar_holiday IS 'GROUP 假日代表日历必须属于同租户且加入该分组';


ALTER TABLE ONLY batch.config_batch_target_receipt
    ADD CONSTRAINT fk_config_batch_receipt_operation FOREIGN KEY (audit_tenant_id, operation_id) REFERENCES batch.config_batch_operation(audit_tenant_id, operation_id) ON DELETE CASCADE;


ALTER TABLE ONLY batch.config_sync_target_receipt
    ADD CONSTRAINT fk_config_sync_receipt_log FOREIGN KEY (audit_tenant_id, log_id) REFERENCES batch.config_sync_log(tenant_id, id) ON DELETE CASCADE;


ALTER TABLE ONLY batch.console_ai_attachment
    ADD CONSTRAINT fk_console_ai_attachment_turn FOREIGN KEY (tenant_id, conversation_id, turn_no) REFERENCES batch.console_ai_turn(tenant_id, conversation_id, turn_no) ON DELETE CASCADE;


ALTER TABLE ONLY batch.console_ai_turn
    ADD CONSTRAINT fk_console_ai_turn_conversation FOREIGN KEY (tenant_id, conversation_id) REFERENCES batch.console_ai_conversation(tenant_id, id) ON DELETE CASCADE;


ALTER TABLE ONLY batch.console_external_identity
    ADD CONSTRAINT fk_console_external_identity_account_tenant FOREIGN KEY (tenant_id, account_id) REFERENCES batch.console_user_account(tenant_id, id) ON DELETE CASCADE;


ALTER TABLE ONLY batch.data_quality_check
    ADD CONSTRAINT fk_dq_check_rule_id FOREIGN KEY (rule_id) REFERENCES batch.data_quality_rule(id) ON DELETE SET NULL;


ALTER TABLE ONLY batch.event_delivery_log
    ADD CONSTRAINT fk_event_delivery_log_outbox_event_reference FOREIGN KEY (tenant_id, outbox_event_id, outbox_event_created_at) REFERENCES batch.outbox_event(tenant_id, id, created_at);


ALTER TABLE ONLY batch.event_outbox_retry
    ADD CONSTRAINT fk_event_outbox_retry_outbox_event_reference FOREIGN KEY (tenant_id, outbox_event_id, outbox_event_created_at) REFERENCES batch.outbox_event(tenant_id, id, created_at);


ALTER TABLE ONLY batch.expected_work_occurrence
    ADD CONSTRAINT fk_expected_work_trigger_request FOREIGN KEY (tenant_id, trigger_request_id) REFERENCES batch.trigger_request(tenant_id, request_id);


COMMENT ON CONSTRAINT fk_expected_work_trigger_request ON batch.expected_work_occurrence IS '租户范围内的来源请求；NO ACTION 可保留预期工作证据，直至执行显式生命周期清理。';


ALTER TABLE ONLY batch.expected_work_occurrence
    ADD CONSTRAINT fk_expected_work_workflow FOREIGN KEY (tenant_id, workflow_definition_id) REFERENCES batch.workflow_definition(tenant_id, id);


ALTER TABLE ONLY batch.external_event_delivery_alias
    ADD CONSTRAINT fk_external_event_delivery_alias_delivery FOREIGN KEY (tenant_id, delivery_id) REFERENCES batch.external_event_delivery(tenant_id, id) ON DELETE CASCADE;


ALTER TABLE ONLY batch.external_event_expectation_definition
    ADD CONSTRAINT fk_external_event_expectation_workflow FOREIGN KEY (tenant_id, workflow_definition_id) REFERENCES batch.workflow_definition(tenant_id, id);


ALTER TABLE ONLY batch.external_event_expectation_definition
    ADD CONSTRAINT fk_external_event_expectation_workflow_calendar FOREIGN KEY (tenant_id, workflow_definition_id, calendar_code) REFERENCES batch.workflow_definition(tenant_id, id, calendar_code);


COMMENT ON CONSTRAINT fk_external_event_expectation_workflow_calendar ON batch.external_event_expectation_definition IS '预期项定义必须使用同一租户 Workflow 的日历；变更父日历前须先显式清理子项。V316 保留原有两列租户外键，并增加更严格的日历身份约束。';


ALTER TABLE ONLY batch.file_channel_health
    ADD CONSTRAINT fk_file_channel_health_config_tenant FOREIGN KEY (tenant_id, channel_code) REFERENCES batch.file_channel_config(tenant_id, channel_code) ON DELETE CASCADE;


COMMENT ON CONSTRAINT fk_file_channel_health_config_tenant ON batch.file_channel_health IS '健康状态必须关联同一租户中的通道配置，并随该通道一并删除。';


ALTER TABLE ONLY batch.file_group_expectation_definition
    ADD CONSTRAINT fk_file_group_expectation_workflow FOREIGN KEY (tenant_id, workflow_definition_id) REFERENCES batch.workflow_definition(tenant_id, id);


ALTER TABLE ONLY batch.file_group_expectation_definition
    ADD CONSTRAINT fk_file_group_expectation_workflow_calendar FOREIGN KEY (tenant_id, workflow_definition_id, calendar_code) REFERENCES batch.workflow_definition(tenant_id, id, calendar_code);


COMMENT ON CONSTRAINT fk_file_group_expectation_workflow_calendar ON batch.file_group_expectation_definition IS '预期项定义必须使用同一租户 Workflow 的日历；变更父日历前须先显式清理子项。V316 保留原有两列租户外键，并增加更严格的日历身份约束。';


ALTER TABLE ONLY batch.job_dependency
    ADD CONSTRAINT fk_job_dependency_downstream FOREIGN KEY (tenant_id, downstream_job_code) REFERENCES batch.job_definition(tenant_id, job_code) ON DELETE CASCADE;


ALTER TABLE ONLY batch.job_dependency
    ADD CONSTRAINT fk_job_dependency_upstream FOREIGN KEY (tenant_id, upstream_job_code) REFERENCES batch.job_definition(tenant_id, job_code);


COMMENT ON CONSTRAINT fk_job_dependency_upstream ON batch.job_dependency IS '租户范围内的上游 Job 定义；NO ACTION 保留有效依赖边，直至显式移除。';


ALTER TABLE ONLY batch.job_dependency_waiting_launch
    ADD CONSTRAINT fk_job_dependency_wait_request FOREIGN KEY (tenant_id, request_id) REFERENCES batch.trigger_request(tenant_id, request_id);


COMMENT ON CONSTRAINT fk_job_dependency_wait_request ON batch.job_dependency_waiting_launch IS '租户范围内的请求身份；NO ACTION 可保留等待中及终态证据，直至执行显式保留期清理。';


ALTER TABLE ONLY batch.job_execution_log
    ADD CONSTRAINT fk_job_execution_log_partition_tenant_instance FOREIGN KEY (tenant_id, job_partition_id, job_instance_id) REFERENCES batch.job_partition(tenant_id, id, job_instance_id) ON DELETE SET NULL (job_partition_id);


COMMENT ON CONSTRAINT fk_job_execution_log_partition_tenant_instance ON batch.job_execution_log IS '关联分区的审计日志必须匹配分区所属租户和 JobInstance；删除分区时仅清除该引用。';


ALTER TABLE batch.job_instance
    ADD CONSTRAINT fk_job_instance_job_definition_tenant FOREIGN KEY (tenant_id, job_definition_id) REFERENCES batch.job_definition(tenant_id, id);


ALTER TABLE batch.job_instance
    ADD CONSTRAINT fk_job_instance_trigger_request_tenant FOREIGN KEY (tenant_id, trigger_request_id) REFERENCES batch.trigger_request(tenant_id, id);


COMMENT ON CONSTRAINT fk_job_instance_trigger_request_tenant ON batch.job_instance IS 'JobInstance 的 TriggerRequest 必须属于同一租户；手工创建的实例可不关联请求。';


ALTER TABLE ONLY batch.job_monitoring_policy
    ADD CONSTRAINT fk_job_monitoring_policy_job_definition_tenant FOREIGN KEY (tenant_id, job_definition_id) REFERENCES batch.job_definition(tenant_id, id) ON DELETE CASCADE;


COMMENT ON CONSTRAINT fk_job_monitoring_policy_job_definition_tenant ON batch.job_monitoring_policy IS '租户范围内的所属 Job 定义；监控策略依赖该定义，并随自身父对象删除。';


ALTER TABLE ONLY batch.job_partition
    ADD CONSTRAINT fk_job_partition_instance_tenant_date FOREIGN KEY (tenant_id, job_instance_id, job_instance_biz_date) REFERENCES batch.job_instance(tenant_id, id, biz_date);


ALTER TABLE ONLY batch.job_partition
    ADD CONSTRAINT fk_job_partition_source_file_tenant FOREIGN KEY (tenant_id, source_file_id) REFERENCES batch.file_record(tenant_id, id);


COMMENT ON CONSTRAINT fk_job_partition_source_file_tenant ON batch.job_partition IS '将热态文件包分区绑定到同一租户拥有的来源文件；冷归档仅保留历史弱引用。';


ALTER TABLE ONLY batch.console_push_job_delivery
    ADD CONSTRAINT fk_job_push_receipt FOREIGN KEY (tenant_id, terminal_event_key) REFERENCES batch.workflow_terminal_notification_receipt(tenant_id, event_key);


ALTER TABLE ONLY batch.job_step_instance
    ADD CONSTRAINT fk_job_step_task_ownership FOREIGN KEY (tenant_id, job_task_id, job_instance_id, job_partition_ref_key) REFERENCES batch.job_task(tenant_id, id, job_instance_id, job_partition_ref_key) ON DELETE CASCADE;


COMMENT ON CONSTRAINT fk_job_step_task_ownership ON batch.job_step_instance IS 'Step 的租户、JobInstance 和可选分区必须与 Task 匹配；删除 Task 时级联删除 Step。';


ALTER TABLE ONLY batch.job_task
    ADD CONSTRAINT fk_job_task_external_outcome_resolution_request FOREIGN KEY (external_outcome_resolution_trigger_request_id) REFERENCES batch.trigger_request(id) ON DELETE SET NULL;


ALTER TABLE ONLY batch.job_task
    ADD CONSTRAINT fk_job_task_instance_tenant_date FOREIGN KEY (tenant_id, job_instance_id, job_instance_biz_date) REFERENCES batch.job_instance(tenant_id, id, biz_date);


ALTER TABLE ONLY batch.job_task
    ADD CONSTRAINT fk_job_task_partition_tenant_instance FOREIGN KEY (tenant_id, job_partition_id, job_instance_id) REFERENCES batch.job_partition(tenant_id, id, job_instance_id) ON DELETE CASCADE;


COMMENT ON CONSTRAINT fk_job_task_partition_tenant_instance ON batch.job_task IS '已分配的分区必须属于同一租户和 JobInstance；不关联分区的任务仍然有效。';


ALTER TABLE ONLY batch.notification_delivery_log
    ADD CONSTRAINT fk_notification_terminal_receipt FOREIGN KEY (tenant_id, terminal_event_key) REFERENCES batch.workflow_terminal_notification_receipt(tenant_id, event_key);


ALTER TABLE ONLY batch.pipeline_progress
    ADD CONSTRAINT fk_pipeline_progress_tenant_pipeline FOREIGN KEY (tenant_id, pipeline_instance_id) REFERENCES batch.pipeline_instance(tenant_id, id) ON DELETE RESTRICT;


ALTER TABLE ONLY batch.readiness_wait
    ADD CONSTRAINT fk_readiness_wait_workflow_definition FOREIGN KEY (tenant_id, workflow_definition_id) REFERENCES batch.workflow_definition(tenant_id, id);


COMMENT ON CONSTRAINT fk_readiness_wait_workflow_definition ON batch.readiness_wait IS '同租户复合外键：等待必须指向真实定义（V247 候选键）；NO ACTION 提供父行删除保护。';


ALTER TABLE ONLY batch.replay_operation_preview_token
    ADD CONSTRAINT fk_replay_operation_preview_operation_tenant FOREIGN KEY (tenant_id, operation_id) REFERENCES batch.replay_operation(tenant_id, id);


ALTER TABLE ONLY batch.batch_day_replay_preview_token
    ADD CONSTRAINT fk_replay_preview_token_session_tenant FOREIGN KEY (tenant_id, session_id) REFERENCES batch.batch_day_replay_session(tenant_id, id);


ALTER TABLE ONLY batch.batch_day_replay_session
    ADD CONSTRAINT fk_replay_session_operation_tenant FOREIGN KEY (tenant_id, replay_operation_id) REFERENCES batch.replay_operation(tenant_id, id);


ALTER TABLE ONLY batch.scheduled_occurrence_catch_up_audit
    ADD CONSTRAINT fk_scheduled_occurrence_catch_up_identity FOREIGN KEY (tenant_id, workflow_definition_id, scheduled_fire_time, biz_date, request_id) REFERENCES batch.scheduled_occurrence(tenant_id, workflow_definition_id, scheduled_fire_time, biz_date, request_id);


ALTER TABLE ONLY batch.scheduled_occurrence_catch_up_audit
    ADD CONSTRAINT fk_scheduled_occurrence_catch_up_occurrence FOREIGN KEY (tenant_id, workflow_definition_id, scheduled_fire_time) REFERENCES batch.scheduled_occurrence(tenant_id, workflow_definition_id, scheduled_fire_time);


ALTER TABLE ONLY batch.scheduled_occurrence_catch_up_audit
    ADD CONSTRAINT fk_scheduled_occurrence_catch_up_request FOREIGN KEY (tenant_id, request_id) REFERENCES batch.trigger_request(tenant_id, request_id);


ALTER TABLE ONLY batch.scheduled_occurrence_coverage
    ADD CONSTRAINT fk_scheduled_occurrence_coverage_workflow FOREIGN KEY (tenant_id, workflow_definition_id) REFERENCES batch.workflow_definition(tenant_id, id);


ALTER TABLE ONLY batch.scheduled_occurrence_fire
    ADD CONSTRAINT fk_scheduled_occurrence_fire_workflow FOREIGN KEY (tenant_id, workflow_definition_id) REFERENCES batch.workflow_definition(tenant_id, id) ON DELETE CASCADE;


ALTER TABLE ONLY batch.scheduled_occurrence
    ADD CONSTRAINT fk_scheduled_occurrence_trigger_request FOREIGN KEY (tenant_id, request_id) REFERENCES batch.trigger_request(tenant_id, request_id);


COMMENT ON CONSTRAINT fk_scheduled_occurrence_trigger_request ON batch.scheduled_occurrence IS '租户范围内的来源请求；NO ACTION 可保留定时触发来源记录，直至执行显式生命周期清理。';


ALTER TABLE ONLY batch.scheduled_occurrence
    ADD CONSTRAINT fk_scheduled_occurrence_workflow FOREIGN KEY (tenant_id, workflow_definition_id) REFERENCES batch.workflow_definition(tenant_id, id);


ALTER TABLE ONLY batch.trigger_misfire_pending
    ADD CONSTRAINT fk_trigger_misfire_pending_catch_up_request FOREIGN KEY (tenant_id, catch_up_request_id) REFERENCES batch.trigger_request(tenant_id, id);


COMMENT ON CONSTRAINT fk_trigger_misfire_pending_catch_up_request ON batch.trigger_misfire_pending IS '租户范围内经批准的补跑请求；NO ACTION 可保留 misfire 来源记录，直至执行显式生命周期清理。';


ALTER TABLE ONLY batch.trigger_misfire_pending
    ADD CONSTRAINT fk_trigger_misfire_pending_workflow FOREIGN KEY (tenant_id, workflow_definition_id) REFERENCES batch.workflow_definition(tenant_id, id);


ALTER TABLE ONLY batch.trigger_outbox_event
    ADD CONSTRAINT fk_trigger_outbox_event_request FOREIGN KEY (tenant_id, request_id) REFERENCES batch.trigger_request(tenant_id, request_id);


COMMENT ON CONSTRAINT fk_trigger_outbox_event_request ON batch.trigger_outbox_event IS '租户范围内的来源 Trigger 请求；NO ACTION 可保留 Outbox 来源记录，直至执行显式保留期清理。';


ALTER TABLE ONLY batch.trigger_request_idempotency_alias
    ADD CONSTRAINT fk_trigger_request_idempotency_alias_request FOREIGN KEY (tenant_id, request_id) REFERENCES batch.trigger_request(tenant_id, request_id) ON DELETE CASCADE;


ALTER TABLE ONLY batch.trigger_request
    ADD CONSTRAINT fk_trigger_request_workflow_definition FOREIGN KEY (tenant_id, workflow_definition_id) REFERENCES batch.workflow_definition(tenant_id, id);


ALTER TABLE ONLY batch.webhook_delivery_log
    ADD CONSTRAINT fk_webhook_terminal_receipt FOREIGN KEY (tenant_id, terminal_event_key) REFERENCES batch.workflow_terminal_notification_receipt(tenant_id, event_key);


ALTER TABLE ONLY batch.workflow_edge
    ADD CONSTRAINT fk_workflow_edge_definition_tenant FOREIGN KEY (tenant_id, workflow_definition_id) REFERENCES batch.workflow_definition(tenant_id, id);


ALTER TABLE ONLY batch.workflow_launch_wait
    ADD CONSTRAINT fk_workflow_launch_wait_definition_tenant FOREIGN KEY (tenant_id, workflow_definition_id) REFERENCES batch.workflow_definition(tenant_id, id);


ALTER TABLE ONLY batch.workflow_launch_wait
    ADD CONSTRAINT fk_workflow_launch_wait_request_retention FOREIGN KEY (tenant_id, request_id) REFERENCES batch.trigger_request(tenant_id, request_id);


COMMENT ON CONSTRAINT fk_workflow_launch_wait_request_retention ON batch.workflow_launch_wait IS '以 NO ACTION 替代级联删除；清理原请求前必须先清理终态等待记录。';


ALTER TABLE ONLY batch.workflow_monitoring_policy
    ADD CONSTRAINT fk_workflow_monitoring_policy_definition FOREIGN KEY (tenant_id, workflow_definition_id) REFERENCES batch.workflow_definition(tenant_id, id) ON DELETE CASCADE;


ALTER TABLE ONLY batch.workflow_node
    ADD CONSTRAINT fk_workflow_node_definition_tenant FOREIGN KEY (tenant_id, workflow_definition_id) REFERENCES batch.workflow_definition(tenant_id, id);


ALTER TABLE ONLY batch.workflow_run
    ADD CONSTRAINT fk_workflow_run_single_job_definition FOREIGN KEY (tenant_id, single_job_definition_id) REFERENCES batch.job_definition(tenant_id, id);


ALTER TABLE ONLY batch.workflow_run
    ADD CONSTRAINT fk_workflow_run_trigger_request FOREIGN KEY (tenant_id, trigger_request_id) REFERENCES batch.trigger_request(tenant_id, id);


ALTER TABLE ONLY batch.workflow_run
    ADD CONSTRAINT fk_workflow_run_workflow_definition_tenant FOREIGN KEY (tenant_id, workflow_definition_id) REFERENCES batch.workflow_definition(tenant_id, id);


ALTER TABLE ONLY batch.workflow_definition
    ADD CONSTRAINT fk_workflow_single_job_definition FOREIGN KEY (tenant_id, single_job_definition_id) REFERENCES batch.job_definition(tenant_id, id);


ALTER TABLE ONLY batch.import_ingress_ownership_binding
    ADD CONSTRAINT import_ingress_ownership_binding_scope_id_fkey FOREIGN KEY (scope_id) REFERENCES batch.import_ingress_ownership(scope_id);


ALTER TABLE ONLY batch.import_ingress_scan_object
    ADD CONSTRAINT import_ingress_scan_object_scope_id_fkey FOREIGN KEY (scope_id) REFERENCES batch.import_ingress_scan_state(scope_id);


ALTER TABLE ONLY batch.import_ingress_scan_state
    ADD CONSTRAINT import_ingress_scan_state_storage_id_fkey FOREIGN KEY (storage_id) REFERENCES batch.import_ingress_scan_storage_lease(storage_id);


ALTER TABLE ONLY batch.pipeline_instance
    ADD CONSTRAINT pipeline_instance_file_id_fkey FOREIGN KEY (file_id) REFERENCES batch.file_record(id);


ALTER TABLE ONLY batch.pipeline_step_run
    ADD CONSTRAINT pipeline_step_run_pipeline_instance_id_fkey FOREIGN KEY (pipeline_instance_id) REFERENCES batch.pipeline_instance(id);


ALTER TABLE ONLY batch.webhook_delivery_log
    ADD CONSTRAINT webhook_delivery_log_subscription_id_fkey FOREIGN KEY (subscription_id) REFERENCES batch.webhook_subscription(id);


ALTER TABLE ONLY batch.workflow_node_run
    ADD CONSTRAINT workflow_node_run_workflow_run_id_fkey FOREIGN KEY (workflow_run_id) REFERENCES batch.workflow_run(id);


ALTER TABLE ONLY quartz.qrtz_blob_triggers
    ADD CONSTRAINT qrtz_blob_triggers_sched_name_trigger_name_trigger_group_fkey FOREIGN KEY (sched_name, trigger_name, trigger_group) REFERENCES quartz.qrtz_triggers(sched_name, trigger_name, trigger_group);


ALTER TABLE ONLY quartz.qrtz_cron_triggers
    ADD CONSTRAINT qrtz_cron_triggers_sched_name_trigger_name_trigger_group_fkey FOREIGN KEY (sched_name, trigger_name, trigger_group) REFERENCES quartz.qrtz_triggers(sched_name, trigger_name, trigger_group);


ALTER TABLE ONLY quartz.qrtz_simple_triggers
    ADD CONSTRAINT qrtz_simple_triggers_sched_name_trigger_name_trigger_group_fkey FOREIGN KEY (sched_name, trigger_name, trigger_group) REFERENCES quartz.qrtz_triggers(sched_name, trigger_name, trigger_group);


ALTER TABLE ONLY quartz.qrtz_simprop_triggers
    ADD CONSTRAINT qrtz_simprop_triggers_sched_name_trigger_name_trigger_grou_fkey FOREIGN KEY (sched_name, trigger_name, trigger_group) REFERENCES quartz.qrtz_triggers(sched_name, trigger_name, trigger_group);


ALTER TABLE ONLY quartz.qrtz_triggers
    ADD CONSTRAINT qrtz_triggers_sched_name_job_name_job_group_fkey FOREIGN KEY (sched_name, job_name, job_group) REFERENCES quartz.qrtz_job_details(sched_name, job_name, job_group);


ALTER TABLE archive.console_operation_audit_archive ENABLE ROW LEVEL SECURITY;

CREATE POLICY console_operation_audit_cold_tenant_isolation ON archive.console_operation_audit_archive USING (((tenant_id)::text = current_setting('app.tenant_id'::text, true))) WITH CHECK (((tenant_id)::text = current_setting('app.tenant_id'::text, true)));


ALTER TABLE archive.console_push_job_delivery_archive ENABLE ROW LEVEL SECURITY;

CREATE POLICY console_push_job_delivery_cold_tenant_isolation ON archive.console_push_job_delivery_archive USING (((tenant_id)::text = current_setting('app.tenant_id'::text, true))) WITH CHECK (((tenant_id)::text = current_setting('app.tenant_id'::text, true)));


ALTER TABLE archive.notification_delivery_log_archive ENABLE ROW LEVEL SECURITY;

CREATE POLICY notification_delivery_log_cold_tenant_isolation ON archive.notification_delivery_log_archive USING (((tenant_id)::text = current_setting('app.tenant_id'::text, true))) WITH CHECK (((tenant_id)::text = current_setting('app.tenant_id'::text, true)));


ALTER TABLE archive.webhook_delivery_log_archive ENABLE ROW LEVEL SECURITY;

CREATE POLICY webhook_delivery_log_cold_tenant_isolation ON archive.webhook_delivery_log_archive USING (((tenant_id)::text = current_setting('app.tenant_id'::text, true))) WITH CHECK (((tenant_id)::text = current_setting('app.tenant_id'::text, true)));


ALTER TABLE archive.workflow_terminal_notification_receipt_archive ENABLE ROW LEVEL SECURITY;

CREATE POLICY workflow_terminal_notification_receipt_cold_tenant_isolation ON archive.workflow_terminal_notification_receipt_archive USING (((tenant_id)::text = current_setting('app.tenant_id'::text, true))) WITH CHECK (((tenant_id)::text = current_setting('app.tenant_id'::text, true)));


ALTER TABLE batch.alert_notification_receipt ENABLE ROW LEVEL SECURITY;

CREATE POLICY alert_notification_receipt_tenant_isolation ON batch.alert_notification_receipt USING (((tenant_id)::text = current_setting('app.tenant_id'::text, true))) WITH CHECK (((tenant_id)::text = current_setting('app.tenant_id'::text, true)));


ALTER TABLE batch.batch_day_catchup_control ENABLE ROW LEVEL SECURITY;

ALTER TABLE batch.batch_day_catchup_control_event ENABLE ROW LEVEL SECURITY;

CREATE POLICY batch_day_catchup_control_event_tenant_isolation ON batch.batch_day_catchup_control_event USING (((tenant_id)::text = current_setting('app.tenant_id'::text, true))) WITH CHECK (((tenant_id)::text = current_setting('app.tenant_id'::text, true)));


CREATE POLICY batch_day_catchup_control_tenant_isolation ON batch.batch_day_catchup_control USING (((tenant_id)::text = current_setting('app.tenant_id'::text, true))) WITH CHECK (((tenant_id)::text = current_setting('app.tenant_id'::text, true)));


ALTER TABLE batch.batch_day_catchup_operation ENABLE ROW LEVEL SECURITY;

CREATE POLICY batch_day_catchup_operation_tenant_isolation ON batch.batch_day_catchup_operation USING (((tenant_id)::text = current_setting('app.tenant_id'::text, true))) WITH CHECK (((tenant_id)::text = current_setting('app.tenant_id'::text, true)));


ALTER TABLE batch.batch_day_open_cursor ENABLE ROW LEVEL SECURITY;

CREATE POLICY batch_day_open_cursor_tenant_isolation ON batch.batch_day_open_cursor USING (((tenant_id)::text = current_setting('app.tenant_id'::text, true))) WITH CHECK (((tenant_id)::text = current_setting('app.tenant_id'::text, true)));


ALTER TABLE batch.calendar_dependency_wait ENABLE ROW LEVEL SECURITY;

CREATE POLICY calendar_dependency_wait_tenant_isolation ON batch.calendar_dependency_wait USING (((tenant_id)::text = current_setting('app.tenant_id'::text, true))) WITH CHECK (((tenant_id)::text = current_setting('app.tenant_id'::text, true)));


ALTER TABLE batch.config_batch_operation ENABLE ROW LEVEL SECURITY;

CREATE POLICY config_batch_operation_tenant_isolation ON batch.config_batch_operation USING (((audit_tenant_id)::text = current_setting('app.tenant_id'::text, true))) WITH CHECK (((audit_tenant_id)::text = current_setting('app.tenant_id'::text, true)));


CREATE POLICY config_batch_receipt_tenant_isolation ON batch.config_batch_target_receipt USING (((tenant_id)::text = current_setting('app.tenant_id'::text, true))) WITH CHECK (((tenant_id)::text = current_setting('app.tenant_id'::text, true)));


ALTER TABLE batch.config_batch_target_receipt ENABLE ROW LEVEL SECURITY;

ALTER TABLE batch.config_cache_invalidation_outbox ENABLE ROW LEVEL SECURITY;

CREATE POLICY config_cache_invalidation_platform ON batch.config_cache_invalidation_outbox TO CURRENT_USER USING (true) WITH CHECK (true);


CREATE POLICY config_sync_receipt_tenant_isolation ON batch.config_sync_target_receipt USING (((tenant_id)::text = current_setting('app.tenant_id'::text, true))) WITH CHECK (((tenant_id)::text = current_setting('app.tenant_id'::text, true)));


ALTER TABLE batch.config_sync_target_receipt ENABLE ROW LEVEL SECURITY;

ALTER TABLE batch.console_ai_attachment ENABLE ROW LEVEL SECURITY;

CREATE POLICY console_ai_attachment_tenant_isolation ON batch.console_ai_attachment USING (((tenant_id)::text = current_setting('app.tenant_id'::text, true))) WITH CHECK (((tenant_id)::text = current_setting('app.tenant_id'::text, true)));


ALTER TABLE batch.console_ai_conversation ENABLE ROW LEVEL SECURITY;

CREATE POLICY console_ai_conversation_tenant_isolation ON batch.console_ai_conversation USING (((tenant_id)::text = current_setting('app.tenant_id'::text, true))) WITH CHECK (((tenant_id)::text = current_setting('app.tenant_id'::text, true)));


ALTER TABLE batch.console_ai_monthly_usage ENABLE ROW LEVEL SECURITY;

CREATE POLICY console_ai_monthly_usage_tenant_isolation ON batch.console_ai_monthly_usage USING (((tenant_id)::text = current_setting('app.tenant_id'::text, true))) WITH CHECK (((tenant_id)::text = current_setting('app.tenant_id'::text, true)));


ALTER TABLE batch.console_ai_object_cleanup ENABLE ROW LEVEL SECURITY;

CREATE POLICY console_ai_object_cleanup_tenant_isolation ON batch.console_ai_object_cleanup USING (((tenant_id)::text = current_setting('app.tenant_id'::text, true))) WITH CHECK (((tenant_id)::text = current_setting('app.tenant_id'::text, true)));


ALTER TABLE batch.console_ai_turn ENABLE ROW LEVEL SECURITY;

CREATE POLICY console_ai_turn_tenant_isolation ON batch.console_ai_turn USING (((tenant_id)::text = current_setting('app.tenant_id'::text, true))) WITH CHECK (((tenant_id)::text = current_setting('app.tenant_id'::text, true)));


ALTER TABLE batch.console_push_job_delivery ENABLE ROW LEVEL SECURITY;

CREATE POLICY console_push_job_delivery_tenant_isolation ON batch.console_push_job_delivery USING (((tenant_id)::text = current_setting('app.tenant_id'::text, true))) WITH CHECK (((tenant_id)::text = current_setting('app.tenant_id'::text, true)));


ALTER TABLE batch.console_usage_daily ENABLE ROW LEVEL SECURITY;

CREATE POLICY console_usage_daily_tenant_isolation ON batch.console_usage_daily USING (((tenant_id)::text = current_setting('app.tenant_id'::text, true))) WITH CHECK (((tenant_id)::text = current_setting('app.tenant_id'::text, true)));


ALTER TABLE batch.expected_work_occurrence ENABLE ROW LEVEL SECURITY;

CREATE POLICY expected_work_tenant_isolation ON batch.expected_work_occurrence USING (((tenant_id)::text = current_setting('app.tenant_id'::text, true))) WITH CHECK (((tenant_id)::text = current_setting('app.tenant_id'::text, true)));


ALTER TABLE batch.external_event_delivery ENABLE ROW LEVEL SECURITY;

ALTER TABLE batch.external_event_delivery_alias ENABLE ROW LEVEL SECURITY;

CREATE POLICY external_event_delivery_alias_tenant_isolation ON batch.external_event_delivery_alias USING (((tenant_id)::text = current_setting('app.tenant_id'::text, true))) WITH CHECK (((tenant_id)::text = current_setting('app.tenant_id'::text, true)));


CREATE POLICY external_event_delivery_tenant_isolation ON batch.external_event_delivery USING (((tenant_id)::text = current_setting('app.tenant_id'::text, true))) WITH CHECK (((tenant_id)::text = current_setting('app.tenant_id'::text, true)));


ALTER TABLE batch.external_event_expectation_definition ENABLE ROW LEVEL SECURITY;

CREATE POLICY external_event_expectation_tenant_isolation ON batch.external_event_expectation_definition USING (((tenant_id)::text = current_setting('app.tenant_id'::text, true))) WITH CHECK (((tenant_id)::text = current_setting('app.tenant_id'::text, true)));


ALTER TABLE batch.file_group_expectation_definition ENABLE ROW LEVEL SECURITY;

CREATE POLICY file_group_expectation_tenant_isolation ON batch.file_group_expectation_definition USING (((tenant_id)::text = current_setting('app.tenant_id'::text, true))) WITH CHECK (((tenant_id)::text = current_setting('app.tenant_id'::text, true)));


ALTER TABLE batch.import_ingress_ownership ENABLE ROW LEVEL SECURITY;

ALTER TABLE batch.import_ingress_ownership_binding ENABLE ROW LEVEL SECURITY;

CREATE POLICY import_ingress_ownership_binding_owner ON batch.import_ingress_ownership_binding TO CURRENT_USER USING (true) WITH CHECK (true);


CREATE POLICY import_ingress_ownership_owner ON batch.import_ingress_ownership TO CURRENT_USER USING (true) WITH CHECK (true);


ALTER TABLE batch.import_ingress_scan_object ENABLE ROW LEVEL SECURITY;

CREATE POLICY import_ingress_scan_object_owner ON batch.import_ingress_scan_object TO CURRENT_USER USING (true) WITH CHECK (true);


ALTER TABLE batch.import_ingress_scan_state ENABLE ROW LEVEL SECURITY;

CREATE POLICY import_ingress_scan_state_owner ON batch.import_ingress_scan_state TO CURRENT_USER USING (true) WITH CHECK (true);


ALTER TABLE batch.import_ingress_scan_storage_lease ENABLE ROW LEVEL SECURITY;

CREATE POLICY import_ingress_scan_storage_owner ON batch.import_ingress_scan_storage_lease TO CURRENT_USER USING (true) WITH CHECK (true);


ALTER TABLE batch.job_dependency ENABLE ROW LEVEL SECURITY;

CREATE POLICY job_dependency_tenant_isolation ON batch.job_dependency USING (((tenant_id)::text = current_setting('app.tenant_id'::text, true))) WITH CHECK (((tenant_id)::text = current_setting('app.tenant_id'::text, true)));


CREATE POLICY job_dependency_wait_tenant_isolation ON batch.job_dependency_waiting_launch USING (((tenant_id)::text = current_setting('app.tenant_id'::text, true))) WITH CHECK (((tenant_id)::text = current_setting('app.tenant_id'::text, true)));


ALTER TABLE batch.job_dependency_waiting_launch ENABLE ROW LEVEL SECURITY;

ALTER TABLE batch.replay_operation ENABLE ROW LEVEL SECURITY;

CREATE POLICY replay_operation_preview_tenant_isolation ON batch.replay_operation_preview_token USING (((tenant_id)::text = current_setting('app.tenant_id'::text, true))) WITH CHECK (((tenant_id)::text = current_setting('app.tenant_id'::text, true)));


ALTER TABLE batch.replay_operation_preview_token ENABLE ROW LEVEL SECURITY;

CREATE POLICY replay_operation_tenant_isolation ON batch.replay_operation USING (((tenant_id)::text = current_setting('app.tenant_id'::text, true))) WITH CHECK (((tenant_id)::text = current_setting('app.tenant_id'::text, true)));


ALTER TABLE batch.scheduled_occurrence_fire ENABLE ROW LEVEL SECURITY;

CREATE POLICY scheduled_occurrence_fire_tenant_isolation ON batch.scheduled_occurrence_fire USING (((tenant_id)::text = current_setting('app.tenant_id'::text, true))) WITH CHECK (((tenant_id)::text = current_setting('app.tenant_id'::text, true)));


ALTER TABLE batch.workflow_launch_wait ENABLE ROW LEVEL SECURITY;

CREATE POLICY workflow_launch_wait_tenant_isolation ON batch.workflow_launch_wait USING (((tenant_id)::text = current_setting('app.tenant_id'::text, true))) WITH CHECK (((tenant_id)::text = current_setting('app.tenant_id'::text, true)));


ALTER TABLE batch.workflow_monitoring_policy ENABLE ROW LEVEL SECURITY;

CREATE POLICY workflow_monitoring_policy_tenant_isolation ON batch.workflow_monitoring_policy USING (((tenant_id)::text = current_setting('app.tenant_id'::text, true))) WITH CHECK (((tenant_id)::text = current_setting('app.tenant_id'::text, true)));


ALTER TABLE batch.workflow_terminal_notification_receipt ENABLE ROW LEVEL SECURITY;

CREATE POLICY workflow_terminal_notification_tenant_isolation ON batch.workflow_terminal_notification_receipt USING (((tenant_id)::text = current_setting('app.tenant_id'::text, true))) WITH CHECK (((tenant_id)::text = current_setting('app.tenant_id'::text, true)));

-- 维持受限函数和控制面对象访问权限。
REVOKE ALL ON batch.tenant_configuration_revision FROM PUBLIC;
REVOKE ALL ON FUNCTION batch.current_tenant_configuration_revision(TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION batch.lock_tenant_configuration_revision(TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION batch.bump_tenant_configuration_revision() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION batch.current_tenant_configuration_revision(TEXT) TO CURRENT_USER;
GRANT EXECUTE ON FUNCTION batch.lock_tenant_configuration_revision(TEXT) TO CURRENT_USER;
REVOKE ALL ON FUNCTION batch.record_scheduled_occurrence_supersession() FROM PUBLIC;
REVOKE ALL ON batch.config_cache_invalidation_outbox FROM PUBLIC;
REVOKE ALL ON SEQUENCE batch.config_cache_invalidation_outbox_id_seq FROM PUBLIC;
REVOKE ALL ON FUNCTION batch.record_config_cache_invalidation() FROM PUBLIC;
REVOKE ALL ON batch.import_ingress_scan_storage_lease, batch.import_ingress_scan_state, batch.import_ingress_scan_object FROM PUBLIC;
REVOKE ALL ON SEQUENCE batch.import_ingress_scan_object_id_seq FROM PUBLIC;
REVOKE ALL ON batch.import_ingress_ownership, batch.import_ingress_ownership_binding FROM PUBLIC;
REVOKE ALL ON SEQUENCE batch.import_ingress_ownership_binding_id_seq FROM PUBLIC;

-- 按初始化月份预建业务分区，父表索引和约束由 PostgreSQL 复制到子分区。
DO $initial_partitions$
DECLARE
    partition_group record;
    month_start date;
    month_end date;
    month_offset integer;
BEGIN
    FOR partition_group IN
        SELECT *
        FROM (VALUES
            ('job_instance', 'job_instance_p_', 'YYYY_MM', -23, 12, 'job_instance_p_default'),
            ('outbox_event', 'outbox_event_p_', 'YYYY_MM', -23, 12, 'outbox_event_p_default'),
            ('console_usage_daily', 'console_usage_daily_', 'YYYYMM', -1, 14, 'console_usage_daily_default')
        ) AS groups(parent_table, child_prefix, name_format, first_month, last_month, default_table)
    LOOP
        FOR month_offset IN partition_group.first_month..partition_group.last_month LOOP
            month_start := date_trunc('month', current_date) + make_interval(months => month_offset);
            month_end := month_start + interval '1 month';
            EXECUTE format(
                'CREATE TABLE batch.%I PARTITION OF batch.%I FOR VALUES FROM (%L) TO (%L)',
                partition_group.child_prefix || to_char(month_start, partition_group.name_format),
                partition_group.parent_table, month_start, month_end
            );
        END LOOP;
        EXECUTE format(
            'CREATE TABLE batch.%I PARTITION OF batch.%I DEFAULT',
            partition_group.default_table, partition_group.parent_table
        );
    END LOOP;
END;
$initial_partitions$;

COMMENT ON TABLE batch.console_usage_daily_default IS '使用率日聚合的默认分区，用于承接尚未创建专用月份分区的数据。';
