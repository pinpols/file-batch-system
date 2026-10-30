-- resource_tag 是按资源类型和租户代码标识目标的多态引用，不能用单个原生 FK 表达。
-- 以同一租户/类型/代码的事务 advisory lock 串行化标签写入和父资源新增、改键、删除；
-- 数据库触发器保护直接 SQL，应用服务使用同一个 lock key 给调用方返回稳定校验错误。

CREATE OR REPLACE FUNCTION batch.resource_tag_identity_lock_key(
    p_tenant_id TEXT,
    p_resource_type TEXT,
    p_resource_code TEXT
)
RETURNS BIGINT
LANGUAGE SQL
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT pg_catalog.hashtextextended(
        p_tenant_id || chr(31) || p_resource_type || chr(31) || p_resource_code,
        0
    )
$$;

CREATE OR REPLACE FUNCTION batch.lock_resource_tag_identity(
    p_tenant_id TEXT,
    p_resource_type TEXT,
    p_resource_code TEXT
)
RETURNS BIGINT
LANGUAGE plpgsql
VOLATILE
SET search_path = pg_catalog, batch, pg_temp
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

CREATE OR REPLACE FUNCTION batch.validate_resource_tag_target()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, batch, pg_temp
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

CREATE OR REPLACE FUNCTION batch.lock_resource_tag_parent_identity()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, batch, pg_temp
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

CREATE OR REPLACE FUNCTION batch.cleanup_missing_resource_tag_parent()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, batch, pg_temp
AS $$
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
$$;

CREATE TRIGGER resource_tag_validate_target
    BEFORE INSERT OR UPDATE OF tenant_id, resource_type, resource_code
    ON batch.resource_tag
    FOR EACH ROW EXECUTE FUNCTION batch.validate_resource_tag_target();

CREATE TRIGGER job_definition_resource_tag_lock
    BEFORE INSERT OR DELETE OR UPDATE OF tenant_id, job_code
    ON batch.job_definition
    FOR EACH ROW EXECUTE FUNCTION batch.lock_resource_tag_parent_identity('JOB', 'job_code');
CREATE TRIGGER job_definition_resource_tag_cleanup
    AFTER DELETE OR UPDATE OF tenant_id, job_code
    ON batch.job_definition
    FOR EACH ROW EXECUTE FUNCTION batch.cleanup_missing_resource_tag_parent(
        'JOB', 'job_code', 'job_definition');

CREATE TRIGGER workflow_definition_resource_tag_lock
    BEFORE INSERT OR DELETE OR UPDATE OF tenant_id, workflow_code
    ON batch.workflow_definition
    FOR EACH ROW EXECUTE FUNCTION batch.lock_resource_tag_parent_identity('WORKFLOW', 'workflow_code');
CREATE TRIGGER workflow_definition_resource_tag_cleanup
    AFTER DELETE OR UPDATE OF tenant_id, workflow_code
    ON batch.workflow_definition
    FOR EACH ROW EXECUTE FUNCTION batch.cleanup_missing_resource_tag_parent(
        'WORKFLOW', 'workflow_code', 'workflow_definition');

CREATE TRIGGER file_channel_resource_tag_lock
    BEFORE INSERT OR DELETE OR UPDATE OF tenant_id, channel_code
    ON batch.file_channel_config
    FOR EACH ROW EXECUTE FUNCTION batch.lock_resource_tag_parent_identity('FILE_CHANNEL', 'channel_code');
CREATE TRIGGER file_channel_resource_tag_cleanup
    AFTER DELETE OR UPDATE OF tenant_id, channel_code
    ON batch.file_channel_config
    FOR EACH ROW EXECUTE FUNCTION batch.cleanup_missing_resource_tag_parent(
        'FILE_CHANNEL', 'channel_code', 'file_channel_config');

CREATE TRIGGER file_template_resource_tag_lock
    BEFORE INSERT OR DELETE OR UPDATE OF tenant_id, template_code
    ON batch.file_template_config
    FOR EACH ROW EXECUTE FUNCTION batch.lock_resource_tag_parent_identity('FILE_TEMPLATE', 'template_code');
CREATE TRIGGER file_template_resource_tag_cleanup
    AFTER DELETE OR UPDATE OF tenant_id, template_code
    ON batch.file_template_config
    FOR EACH ROW EXECUTE FUNCTION batch.cleanup_missing_resource_tag_parent(
        'FILE_TEMPLATE', 'template_code', 'file_template_config');

COMMENT ON FUNCTION batch.lock_resource_tag_identity(TEXT, TEXT, TEXT) IS
    '将多态 resource_tag 写入与父资源身份生命周期变更串行化。';
COMMENT ON FUNCTION batch.validate_resource_tag_target() IS
    '拒绝无法按租户、类型和编码解析到受支持资源的 resource_tag 记录。';
COMMENT ON FUNCTION batch.cleanup_missing_resource_tag_parent() IS
    '仅在某租户、类型和编码对应的最后一条资源记录被删除或重命名后，才移除对应 resource_tag。';
