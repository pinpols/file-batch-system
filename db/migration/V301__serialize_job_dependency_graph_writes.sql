-- 防止并发依赖写入组合成环。应用层图校验
-- 使用相同的租户级事务锁；此触发器也保护直接 SQL 和旧版
-- 标量同步路径。

CREATE OR REPLACE FUNCTION batch.guard_job_dependency_acyclic()
RETURNS trigger
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

CREATE TRIGGER trg_guard_job_dependency_acyclic
    BEFORE INSERT OR UPDATE OF tenant_id, downstream_job_code, upstream_job_code
    ON batch.job_dependency
    FOR EACH ROW
    EXECUTE FUNCTION batch.guard_job_dependency_acyclic();

COMMENT ON FUNCTION batch.guard_job_dependency_acyclic() IS
    '按租户串行化依赖图写入，并拒绝会形成环的边，包括直接 SQL 和旧式同步写入。';

COMMENT ON TRIGGER trg_guard_job_dependency_acyclic ON batch.job_dependency IS
    '在租户级事务 advisory lock 下约束 Job 依赖图更新，保持依赖图无环。';
