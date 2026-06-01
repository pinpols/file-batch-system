-- =========================================================
-- V159: batch.custom_task_type_registry + archive 镜像
--
-- 依据: docs/plans/sdk-roadmap-2026-h2.md §5.1 M3.1 任务 3.1.1
--
-- 背景:
--   SDK Phase 3 — 租户自定义 taskType 注册。租户 worker 在 register 时
--   上报 taskTypes[].descriptor(参数 defaults / input schema / 模板变量 / 版本),
--   orchestrator upsert 到本表(source=SDK_DECLARED)。console / 派单据此:
--     - console 拖节点 → 按 descriptor.inputSchema 渲染表单(M3.2)
--     - 派单合并 defaults + node.parameters + 模板替换(M3.1 任务 3.1.5)
--
--   descriptor 全文存 JSONB(随 SDK 演进不需频繁加列);仅把查询/索引需要的
--   字段(task_type_code / display_name / descriptor_version / source / status)提为顶层列。
--
--   archive 镜像 — CLAUDE.md 红线(热表 batch.* 与 archive.*_archive 1:1),
--   LIKE INCLUDING ALL 与 V71 风格一致,并登记到 ArchiveSchemaDriftCheck.ARCHIVED_TABLES。
-- =========================================================

CREATE TABLE IF NOT EXISTS batch.custom_task_type_registry (
    id                       BIGSERIAL    PRIMARY KEY,
    tenant_id                VARCHAR(64)  NOT NULL,
    task_type_code           VARCHAR(128) NOT NULL,
    display_name             VARCHAR(256),
    descriptor               JSONB        NOT NULL,
    descriptor_version       VARCHAR(32),
    source                   VARCHAR(32)  NOT NULL DEFAULT 'SDK_DECLARED',
    declared_by_worker_code  VARCHAR(128),
    status                   VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    first_declared_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_declared_at         TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_custom_task_type_registry_tenant_code UNIQUE (tenant_id, task_type_code),
    CONSTRAINT ck_custom_task_type_registry_source CHECK (source IN ('SDK_DECLARED', 'MANUAL')),
    CONSTRAINT ck_custom_task_type_registry_status CHECK (status IN ('ACTIVE', 'DEPRECATED'))
);

COMMENT ON TABLE  batch.custom_task_type_registry IS 'SDK Phase 3 — 租户自定义 taskType 注册表(descriptor 全文 JSONB + 顶层查询列)';
COMMENT ON COLUMN batch.custom_task_type_registry.task_type_code          IS 'taskType code,命名 tenant_<tenantId>_<verb>(roadmap §3.1)';
COMMENT ON COLUMN batch.custom_task_type_registry.descriptor              IS 'SdkTaskTypeDescriptor 全文:label / defaults / inputSchema / templateVars / version';
COMMENT ON COLUMN batch.custom_task_type_registry.descriptor_version      IS 'descriptor 语义版本(变更检测,只在版本变化时刷新存档)';
COMMENT ON COLUMN batch.custom_task_type_registry.source                  IS 'SDK_DECLARED(worker 上报)/ MANUAL(运营后台手填)';
COMMENT ON COLUMN batch.custom_task_type_registry.declared_by_worker_code IS '最近一次上报该 descriptor 的 worker code';
COMMENT ON COLUMN batch.custom_task_type_registry.status                  IS 'ACTIVE / DEPRECATED(不兼容升级灰度,旧 code 保留 30 天)';

-- 派单 / console 查询主路径:按 (tenant_id, status) 列 taskType
CREATE INDEX IF NOT EXISTS idx_custom_task_type_registry_tenant_status
    ON batch.custom_task_type_registry (tenant_id, status);

-- ---------------------------------------------------------
-- archive 镜像(CLAUDE.md 红线;LIKE INCLUDING ALL 与 V71 一致)
-- ---------------------------------------------------------
CREATE TABLE IF NOT EXISTS archive.custom_task_type_registry_archive
    (LIKE batch.custom_task_type_registry INCLUDING DEFAULTS INCLUDING GENERATED INCLUDING IDENTITY INCLUDING CONSTRAINTS);

-- LIKE INCLUDING CONSTRAINTS 不复制 PRIMARY KEY(归档 UPSERT ON CONFLICT(id) 依赖,见 V140 同款补 PK)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        JOIN pg_namespace n ON n.oid = t.relnamespace
        WHERE n.nspname = 'archive'
          AND t.relname = 'custom_task_type_registry_archive'
          AND c.contype = 'p'
    ) THEN
        ALTER TABLE archive.custom_task_type_registry_archive
            ADD CONSTRAINT pk_custom_task_type_registry_archive PRIMARY KEY (id);
    END IF;
END $$;

COMMENT ON TABLE archive.custom_task_type_registry_archive IS
    'V159 archive mirror of batch.custom_task_type_registry';
