-- V177: workflow 簇 3 张表复合 PK 化（Citus distributed 前置）
--
-- 目的：将 workflow 簇 3 张表的 PRIMARY KEY 从单列 (id) 改为复合 (tenant_id, id)，
--       这是 Citus distributed table 的前置条件（分片键必须在 PK 中）。
-- 参见：docs/analysis/citus-poc-gates-2026-06-11.md
--
-- === 量测结果 ===
--
--   · workflow_run
--       当前 PK：workflow_run_pkey (id)  目标：(tenant_id, id)
--       已有 UK：uk_workflow_run_tenant_id (tenant_id, id)（V142 加，先 DROP 再改 PK 后重建）
--       入站 FK：
--         workflow_node_run_workflow_run_id_fkey (workflow_run_id → id) ON DELETE NO ACTION（V5 自动命名）
--       出站 FK：
--         workflow_run_related_job_instance_id_fkey → V171 已 DROP，不重建
--         workflow_run_workflow_definition_id_fkey → workflow_definition PK 仍单列，保持不动
--
--   · approval_command
--       当前 PK：approval_command_pkey (id)  目标：(tenant_id, id)
--       无 FK 约束（出站/入站均无）
--       无 archive 表（V71 清单不含，无需镜像）
--
--   · workflow_node_run（②类豁免，Citus 下失效）
--       当前无 tenant_id 列
--       步骤：ADD COLUMN → 回填 → SET NOT NULL → PK 改复合 → 重建 FK
--       当前 PK：workflow_node_run_pkey (id)  目标：(tenant_id, id)
--       出站 FK → workflow_run(id)：本迁移后 workflow_run PK 变复合 → 重建复合
--       archive 镜像：archive.workflow_node_run_archive 同步补 tenant_id 列（CLAUDE.md 1:1 字段镜像要求）
--
-- === FK 处置清单 ===
--
-- 本波 DROP 并重建复合 FK：
--   · workflow_node_run_workflow_run_id_fkey (workflow_node_run.workflow_run_id → workflow_run) NO ACTION
--
-- 保持不动（FK 所指表 PK 仍单列）：
--   · workflow_run_workflow_definition_id_fkey → workflow_definition PK=(id)
--
-- === mapper 缺口决策 ===
--
-- WorkflowNodeRunMapper.insert：INSERT 列不含 tenant_id，需通过子查询从 workflow_run 取值；
-- 调用链（DefaultTaskOutcomeService.recordNodeRunReady/Start/Finish 等 10 处）
-- 仅传 workflowRunId，改 mapper XML 通过子查询自携 tenant_id，无需改接口签名。
--
-- 量测：入站 FK 合计 1 条重建复合；workflow_node_run 回填行数由 workflow_run 关联得。
--
-- 禁 psql 元命令；禁 BEGIN/COMMIT（Flyway 管事务）

-- ============================================================
-- 0. workflow_node_run 补 tenant_id（特殊表：原无此列）
-- ============================================================

ALTER TABLE batch.workflow_node_run
    ADD COLUMN tenant_id VARCHAR(64);

UPDATE batch.workflow_node_run nr
    SET tenant_id = wr.tenant_id
    FROM batch.workflow_run wr
    WHERE nr.workflow_run_id = wr.id;

ALTER TABLE batch.workflow_node_run
    ALTER COLUMN tenant_id SET NOT NULL;

-- ============================================================
-- 1. workflow_run
--    当前 PK：workflow_run_pkey (id)  目标：(tenant_id, id)
--    先 DROP 入站 FK（workflow_node_run → workflow_run），再改 PK
--    已有 uk_workflow_run_tenant_id(V142) 也需先 DROP（PK 与其重叠）
-- ============================================================

-- DROP 入站 FK（改 PK 前必须先 DROP 所有引用此 PK 的 FK）
ALTER TABLE batch.workflow_node_run
    DROP CONSTRAINT IF EXISTS workflow_node_run_workflow_run_id_fkey;

-- DROP V142 加的兜底 UNIQUE（与新 PK 语义重复，合并进 PK 后无需保留）
ALTER TABLE batch.workflow_run
    DROP CONSTRAINT IF EXISTS uk_workflow_run_tenant_id;

ALTER TABLE batch.workflow_run DROP CONSTRAINT workflow_run_pkey;
ALTER TABLE batch.workflow_run ADD CONSTRAINT workflow_run_pkey
    PRIMARY KEY (tenant_id, id);

-- ============================================================
-- 2. workflow_node_run
--    步骤 0 已补 tenant_id 列；出站 FK 在步骤 1 已 DROP
--    PK 改复合后重建复合 FK → workflow_run
-- ============================================================

ALTER TABLE batch.workflow_node_run DROP CONSTRAINT workflow_node_run_pkey;
ALTER TABLE batch.workflow_node_run ADD CONSTRAINT workflow_node_run_pkey
    PRIMARY KEY (tenant_id, id);

-- 重建复合 FK（workflow_node_run → workflow_run）
ALTER TABLE batch.workflow_node_run
    ADD CONSTRAINT workflow_node_run_workflow_run_id_fkey
    FOREIGN KEY (tenant_id, workflow_run_id) REFERENCES batch.workflow_run (tenant_id, id);

-- ============================================================
-- 3. approval_command
--    当前 PK：approval_command_pkey (id)  目标：(tenant_id, id)
--    无 FK 约束，无 archive 表
-- ============================================================

ALTER TABLE batch.approval_command DROP CONSTRAINT approval_command_pkey;
ALTER TABLE batch.approval_command ADD CONSTRAINT approval_command_pkey
    PRIMARY KEY (tenant_id, id);

-- ============================================================
-- 4. archive 镜像同步
--    CLAUDE.md：热表 batch.* 与 archive.*_archive 必须 1:1 字段镜像
--    workflow_run_archive：V142 已加 uk_workflow_run_archive_tenant_id；
--                         pk_workflow_run_archive(V71) 仍单列，同步改复合 PK
--    workflow_node_run_archive：补 tenant_id 列（workflow_node_run 本迁移加列）
-- ============================================================

-- 4a. archive.workflow_run_archive：改复合 PK
--     先 DROP V142 加的 UNIQUE（与新 PK 重叠），再改 PK
ALTER TABLE archive.workflow_run_archive
    DROP CONSTRAINT IF EXISTS uk_workflow_run_archive_tenant_id;

ALTER TABLE archive.workflow_run_archive DROP CONSTRAINT pk_workflow_run_archive;
ALTER TABLE archive.workflow_run_archive ADD CONSTRAINT pk_workflow_run_archive
    PRIMARY KEY (tenant_id, id);

-- 4b. archive.workflow_node_run_archive：补 tenant_id 列（与热表镜像对齐）
ALTER TABLE archive.workflow_node_run_archive
    ADD COLUMN tenant_id VARCHAR(64);
