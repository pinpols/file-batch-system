-- 日历依赖只能绑定同租户已定义的上下游日历。

ALTER TABLE batch.calendar_dependency
    ADD CONSTRAINT fk_calendar_dependency_upstream_calendar
        FOREIGN KEY (tenant_id, upstream_code)
        REFERENCES batch.business_calendar (tenant_id, calendar_code)
        NOT VALID,
    ADD CONSTRAINT fk_calendar_dependency_downstream_calendar
        FOREIGN KEY (tenant_id, downstream_code)
        REFERENCES batch.business_calendar (tenant_id, calendar_code)
        NOT VALID;

ALTER TABLE batch.calendar_dependency
    VALIDATE CONSTRAINT fk_calendar_dependency_upstream_calendar;
ALTER TABLE batch.calendar_dependency
    VALIDATE CONSTRAINT fk_calendar_dependency_downstream_calendar;

CREATE OR REPLACE FUNCTION batch.guard_calendar_dependency_acyclic()
RETURNS trigger
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

CREATE TRIGGER trg_guard_calendar_dependency_acyclic
    BEFORE INSERT OR UPDATE OF tenant_id, upstream_code, downstream_code, enabled
    ON batch.calendar_dependency
    FOR EACH ROW
    EXECUTE FUNCTION batch.guard_calendar_dependency_acyclic();

COMMENT ON FUNCTION batch.guard_calendar_dependency_acyclic() IS
    '按租户串行化启用的日历依赖写入，并拒绝形成依赖环';
COMMENT ON TRIGGER trg_guard_calendar_dependency_acyclic ON batch.calendar_dependency IS
    '并发写入时阻止启用的日历依赖形成环';
