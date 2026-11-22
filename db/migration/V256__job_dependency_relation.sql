-- =====================================================================
-- V256：规范化定时作业依赖，同时保留旧标量列。
-- 迁移后的所有依赖均保留现有的同 bizDate EFFECTIVE 结果门禁。
-- =====================================================================

CREATE TABLE batch.job_dependency (
    tenant_id             VARCHAR(64)  NOT NULL,
    downstream_job_code   VARCHAR(128) NOT NULL,
    upstream_job_code     VARCHAR(128) NOT NULL,
    dependency_order      SMALLINT     NOT NULL,
    readiness_mode        VARCHAR(32)  NOT NULL DEFAULT 'EFFECTIVE_RESULT',
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_job_dependency PRIMARY KEY
        (tenant_id, downstream_job_code, upstream_job_code),
    CONSTRAINT uk_job_dependency_order UNIQUE
        (tenant_id, downstream_job_code, dependency_order),
    CONSTRAINT fk_job_dependency_downstream FOREIGN KEY (tenant_id, downstream_job_code)
        REFERENCES batch.job_definition (tenant_id, job_code) ON DELETE CASCADE,
    CONSTRAINT ck_job_dependency_not_self CHECK (downstream_job_code <> upstream_job_code),
    CONSTRAINT ck_job_dependency_order CHECK (dependency_order >= 0),
    CONSTRAINT ck_job_dependency_readiness_mode CHECK (readiness_mode = 'EFFECTIVE_RESULT')
);

CREATE INDEX idx_job_dependency_upstream
    ON batch.job_dependency (tenant_id, upstream_job_code, downstream_job_code);

INSERT INTO batch.job_dependency (
    tenant_id, downstream_job_code, upstream_job_code, dependency_order, readiness_mode
)
SELECT tenant_id, job_code, btrim(depends_on_job_code), 0, 'EFFECTIVE_RESULT'
FROM batch.job_definition
WHERE nullif(btrim(depends_on_job_code), '') IS NOT NULL
ON CONFLICT (tenant_id, downstream_job_code, upstream_job_code) DO NOTHING;

CREATE OR REPLACE FUNCTION batch.sync_legacy_job_dependency()
RETURNS trigger
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

CREATE TRIGGER trg_sync_legacy_job_dependency
    AFTER INSERT OR UPDATE OF depends_on_job_code ON batch.job_definition
    FOR EACH ROW EXECUTE FUNCTION batch.sync_legacy_job_dependency();

ALTER TABLE batch.job_dependency ENABLE ROW LEVEL SECURITY;
ALTER TABLE batch.job_dependency FORCE ROW LEVEL SECURITY;
CREATE POLICY job_dependency_tenant_isolation ON batch.job_dependency
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

COMMENT ON TABLE batch.job_dependency IS
    '作业间调度依赖；一个下游可声明多个上游，调度 fire 需全部满足 readiness_mode';
COMMENT ON COLUMN batch.job_dependency.dependency_order IS
    'Console 展示和验证时的稳定顺序；依赖判断采用 AND 语义';
COMMENT ON COLUMN batch.job_dependency.readiness_mode IS
    '本版本固定 EFFECTIVE_RESULT，与旧 depends_on_job_code 的 readiness 语义一致';
