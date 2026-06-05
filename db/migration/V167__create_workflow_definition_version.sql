-- =========================================================
-- V167: batch.workflow_definition_version + archive 镜像
--
-- 依据: workflow-dag-designer Polish 阶段 — 版本 diff 闭环
--
-- 背景:
--   `PUT /api/console/workflow-definitions/{id}/full` 已实现 version 自增(V128
--   updateAndBumpVersion),但只保留主表「当前 version」一行,历史快照丢失。
--   FE Polish 版本 diff 页因此降级为「当前 vs 空」。本表承接每次 fullUpdate
--   成功后的全量快照(nodes / edges JSONB),让 `/{id}/versions` +
--   `/{id}/versions/{version}` 真实可读多版本。
--
--   nodes / edges 全文存 JSONB(快照不需关系约束 / 不查询字段,只整体反序列化);
--   workflow_definition_id 是反向引用(主表 id),便于按定义维度拉历史。
--
--   archive 镜像 — CLAUDE.md 红线(热表 batch.* 与 archive.*_archive 1:1),
--   LIKE INCLUDING ALL 与 V159 / V165 风格一致,登记到
--   ArchiveSchemaDriftCheck.ARCHIVED_TABLES。
--
--   多租隔离(CLAUDE.md 硬约束):tenant_id NOT NULL,UNIQUE 含 tenant_id。
--
-- 关联 PR: feat(console-api): workflow-definition version history 闭环
-- =========================================================

CREATE TABLE IF NOT EXISTS batch.workflow_definition_version (
    id                       BIGSERIAL    PRIMARY KEY,
    tenant_id                VARCHAR(64)  NOT NULL,
    workflow_definition_id   BIGINT       NOT NULL,
    workflow_code            VARCHAR(128) NOT NULL,
    version                  INTEGER      NOT NULL,
    workflow_name            VARCHAR(256),
    workflow_type            VARCHAR(32),
    enabled                  BOOLEAN,
    nodes_json               JSONB        NOT NULL,
    edges_json               JSONB        NOT NULL,
    saved_by                 VARCHAR(64),
    saved_at                 TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    summary                  VARCHAR(512),
    CONSTRAINT uk_workflow_definition_version_tenant_def_ver
        UNIQUE (tenant_id, workflow_definition_id, version)
);

COMMENT ON TABLE  batch.workflow_definition_version IS
    'workflow-dag-designer Polish — fullUpdate 每次写入主表 version+1 后,同事务追加一行快照;FE diff 页拉历史版本';
COMMENT ON COLUMN batch.workflow_definition_version.workflow_definition_id IS '反向引用 batch.workflow_definition.id(主表)';
COMMENT ON COLUMN batch.workflow_definition_version.version                IS '与主表 version 同步;每次 fullUpdate 后 += 1';
COMMENT ON COLUMN batch.workflow_definition_version.nodes_json             IS '整 nodes 列表序列化(ObjectMapper.writeValueAsString),反序列化由 console-api 负责';
COMMENT ON COLUMN batch.workflow_definition_version.edges_json             IS '整 edges 列表序列化(ObjectMapper.writeValueAsString),反序列化由 console-api 负责';
COMMENT ON COLUMN batch.workflow_definition_version.saved_by               IS '触发 fullUpdate 的 console 账号(SecurityContext.username)';
COMMENT ON COLUMN batch.workflow_definition_version.summary                IS '可空,描述本次改动;Spike 阶段 FE 未提交字段,留空预留';

-- 列表主路径:按 (workflow_definition_id) 拉所有 version desc
CREATE INDEX IF NOT EXISTS idx_workflow_definition_version_def_ver_desc
    ON batch.workflow_definition_version (workflow_definition_id, version DESC);

-- ---------------------------------------------------------
-- archive 镜像(CLAUDE.md 红线;LIKE INCLUDING ALL 与 V159 / V165 一致)
-- ---------------------------------------------------------
CREATE TABLE IF NOT EXISTS archive.workflow_definition_version_archive
    (LIKE batch.workflow_definition_version INCLUDING DEFAULTS INCLUDING GENERATED INCLUDING IDENTITY INCLUDING CONSTRAINTS);

-- LIKE INCLUDING CONSTRAINTS 不复制 PRIMARY KEY(归档 UPSERT ON CONFLICT(id) 依赖)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        JOIN pg_namespace n ON n.oid = t.relnamespace
        WHERE n.nspname = 'archive'
          AND t.relname = 'workflow_definition_version_archive'
          AND c.contype = 'p'
    ) THEN
        ALTER TABLE archive.workflow_definition_version_archive
            ADD CONSTRAINT pk_workflow_definition_version_archive PRIMARY KEY (id);
    END IF;
END $$;

COMMENT ON TABLE archive.workflow_definition_version_archive IS
    'V167 archive mirror of batch.workflow_definition_version';
