-- 为租户配置包预览与 Apply 建立数据库级版本栅栏。
-- 所有配置写入口都由表触发器递增版本；Apply 锁定版本行后再比较，避免并发写入静默覆盖。
CREATE TABLE batch.tenant_configuration_revision (
    tenant_id TEXT PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_tenant_configuration_revision_nonnegative CHECK (revision >= 0)
);

COMMENT ON TABLE batch.tenant_configuration_revision IS
    '租户配置写入版本，用于配置包预览与 Apply 的并发冲突检测。';
COMMENT ON COLUMN batch.tenant_configuration_revision.tenant_id IS
    '版本所属租户；只保存并发栅栏元数据，不包含业务配置。';
COMMENT ON COLUMN batch.tenant_configuration_revision.revision IS
    '租户配置任一受管表发生写入时单调递增。';

CREATE OR REPLACE FUNCTION batch.current_tenant_configuration_revision(p_tenant_id TEXT)
RETURNS BIGINT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = batch, pg_temp
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

CREATE OR REPLACE FUNCTION batch.lock_tenant_configuration_revision(p_tenant_id TEXT)
RETURNS BIGINT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = batch, pg_temp
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

CREATE OR REPLACE FUNCTION batch.bump_tenant_configuration_revision()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = batch, pg_temp
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
        IF old_tenant_id IS NULL AND TG_TABLE_NAME = 'pipeline_step_definition' THEN
            SELECT tenant_id INTO old_tenant_id
            FROM batch.pipeline_definition
            WHERE id = (old_row ->> 'pipeline_definition_id')::BIGINT;
        END IF;
    END IF;
    IF TG_OP <> 'DELETE' THEN
        new_row := to_jsonb(NEW);
        new_tenant_id := new_row ->> 'tenant_id';
        IF new_tenant_id IS NULL AND TG_TABLE_NAME = 'pipeline_step_definition' THEN
            SELECT tenant_id INTO new_tenant_id
            FROM batch.pipeline_definition
            WHERE id = (new_row ->> 'pipeline_definition_id')::BIGINT;
        END IF;
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

DO $$
DECLARE
    config_table TEXT;
    config_tables TEXT[] := ARRAY[
        'resource_queue',
        'business_calendar',
        'calendar_holiday',
        'batch_window',
        'job_definition',
        'job_monitoring_policy',
        'file_channel_config',
        'file_template_config',
        'pipeline_definition',
        'pipeline_step_definition',
        'workflow_definition',
        'workflow_node',
        'workflow_edge'
    ];
BEGIN
    FOREACH config_table IN ARRAY config_tables LOOP
        EXECUTE format(
            'CREATE TRIGGER %I AFTER INSERT OR UPDATE OR DELETE ON batch.%I '
            'FOR EACH ROW EXECUTE FUNCTION batch.bump_tenant_configuration_revision()',
            'trg_' || config_table || '_tenant_config_revision',
            config_table);
    END LOOP;
END;
$$;

-- 函数只暴露租户配置版本，不开放版本表本身；业务表触发器由定义者权限维护版本。
REVOKE ALL ON batch.tenant_configuration_revision FROM PUBLIC;
REVOKE ALL ON FUNCTION batch.current_tenant_configuration_revision(TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION batch.lock_tenant_configuration_revision(TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION batch.bump_tenant_configuration_revision() FROM PUBLIC;

-- Flyway 与应用共用平台数据库账号；仅授权迁移执行账号，避免其他数据库角色探测或锁定任意租户版本。
GRANT EXECUTE ON FUNCTION batch.current_tenant_configuration_revision(TEXT) TO CURRENT_USER;
GRANT EXECUTE ON FUNCTION batch.lock_tenant_configuration_revision(TEXT) TO CURRENT_USER;
