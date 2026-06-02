-- =========================================================
-- V165: batch.atomic_task_config + archive 镜像
--
-- 依据: Round-1 TOP-8 / Round-3 #5  B.2 atomic 节点写库 scaffold
--
-- 背景:
--   console FE 2-B 工作流编辑器目前只能从 ConsoleAtomicTaskTypeSchemaService
--   读到 4 类内置原子任务(sql/shell/stored_proc/http)的参数 schema,
--   填好的 parameters 没有可保存的承载表 —— 用户配完一离开页就丢。
--
--   本表承接「租户保存好的 atomic 节点配置」,FE 给名字 + 选 taskType +
--   填 parameters JSONB,后端按 schema 校验后落库,下次编辑器直接拉出来挂到
--   workflow_node 上,免重复填。
--
--   parameters 全文存 JSONB(随 schema 演进不需频繁加列);凭据字段由
--   SensitiveDataValidator(#242)静态拒入,不允许 password / secret /
--   apiKey 等关键字落库。
--
--   archive 镜像 — CLAUDE.md 红线(热表 batch.* 与 archive.*_archive 1:1),
--   LIKE INCLUDING ALL 与 V159 风格一致,登记到 ArchiveSchemaDriftCheck.ARCHIVED_TABLES。
--
--   多租隔离(CLAUDE.md 硬约束):tenant_id NOT NULL,UNIQUE 含 tenant_id。
--
-- 关联 PR:feat(console-api): R3-5 atomic_task_config 写库 scaffold
-- =========================================================

CREATE TABLE IF NOT EXISTS batch.atomic_task_config (
    id            BIGSERIAL    PRIMARY KEY,
    tenant_id     VARCHAR(64)  NOT NULL,
    task_type     VARCHAR(64)  NOT NULL,
    name          VARCHAR(128) NOT NULL,
    parameters    JSONB        NOT NULL,
    created_by    VARCHAR(128),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_atomic_task_config_tenant_type_name UNIQUE (tenant_id, task_type, name)
);

COMMENT ON TABLE  batch.atomic_task_config IS
    'R3-5 / Round-1 TOP-8 — 租户保存的 atomic 节点(sql/shell/stored_proc/http)配置;FE 2-B 编辑器拉取后挂 workflow_node';
COMMENT ON COLUMN batch.atomic_task_config.task_type  IS '内置原子 taskType:sql / stored_proc / shell / http(与 ConsoleAtomicTaskTypeSchemaService.CATALOG 对齐)';
COMMENT ON COLUMN batch.atomic_task_config.name       IS '租户视角的配置名(同租户同 task_type 内唯一)';
COMMENT ON COLUMN batch.atomic_task_config.parameters IS 'parameters JSONB,key 必须落 schema.parameters[].name 集合,凭据字段被 SensitiveDataValidator 拒入';
COMMENT ON COLUMN batch.atomic_task_config.created_by IS '创建人 console 账号(UPDATE 不改,仅 INSERT 期间写入)';

-- 列表主路径:按 (tenant_id, task_type) 过滤
CREATE INDEX IF NOT EXISTS idx_atomic_task_config_tenant_type
    ON batch.atomic_task_config (tenant_id, task_type);

-- ---------------------------------------------------------
-- archive 镜像(CLAUDE.md 红线;LIKE INCLUDING ALL 与 V159 一致)
-- ---------------------------------------------------------
CREATE TABLE IF NOT EXISTS archive.atomic_task_config_archive
    (LIKE batch.atomic_task_config INCLUDING DEFAULTS INCLUDING GENERATED INCLUDING IDENTITY INCLUDING CONSTRAINTS);

-- LIKE INCLUDING CONSTRAINTS 不复制 PRIMARY KEY(归档 UPSERT ON CONFLICT(id) 依赖)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        JOIN pg_namespace n ON n.oid = t.relnamespace
        WHERE n.nspname = 'archive'
          AND t.relname = 'atomic_task_config_archive'
          AND c.contype = 'p'
    ) THEN
        ALTER TABLE archive.atomic_task_config_archive
            ADD CONSTRAINT pk_atomic_task_config_archive PRIMARY KEY (id);
    END IF;
END $$;

COMMENT ON TABLE archive.atomic_task_config_archive IS
    'V165 archive mirror of batch.atomic_task_config';
