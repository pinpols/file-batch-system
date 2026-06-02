-- =========================================================
-- V164: batch.pipeline_progress + archive 镜像
--
-- 依据: ADR-038 §决策一 ProcessingPosition 模型 (2026-06-02 翻案 Accepted)
--
-- 背景:
--   平台 worker Import LOAD / Export GENERATE 当前 loadedCount 只活在内存
--   变量里,任务崩溃 / lease 回收后重派,下一个 worker 从第 0 行重头跑。
--   百万行级真实任务下,业务库重压 + cursor 重头分页冲击 SLA。
--
--   本表持久化 (tenant_id, pipeline_instance_id, stage) 三元组对应的处理位点:
--     - Import LOAD:position_marker = 已处理到的行号(staging 文件 append-only,行号稳定)
--     - Export GENERATE:position_marker = 序列化的 cursor(plugin 的 nextCursor)
--
--   续跑契约(决策二):chunk / page 业务写与本表位点更新合到同一事务,
--   保证不重不漏。Export 写文件非事务,走"分片临时文件 + 已确认页位点"补偿。
--
--   兼容性:无记录时退化为今天的行为(从 0 跑),老任务无感知。
--   灰度开关 batch.worker.checkpoint.enabled (默认 false) 在 P2/P3 加。
--
--   archive 镜像 — CLAUDE.md 红线(热表 batch.* 与 archive.*_archive 1:1),
--   LIKE INCLUDING ALL 与 V159 风格一致,并登记到 ArchiveSchemaDriftCheck.ARCHIVED_TABLES。
-- =========================================================

CREATE TABLE IF NOT EXISTS batch.pipeline_progress (
    id                   BIGSERIAL    PRIMARY KEY,
    tenant_id            VARCHAR(64)  NOT NULL,
    pipeline_instance_id BIGINT       NOT NULL,
    stage                VARCHAR(32)  NOT NULL,
    position_marker      VARCHAR(512),
    processed_count      BIGINT       NOT NULL DEFAULT 0,
    completed            BOOLEAN      NOT NULL DEFAULT FALSE,
    completed_at         TIMESTAMPTZ,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_pipeline_progress_tenant_instance_stage
        UNIQUE (tenant_id, pipeline_instance_id, stage),
    CONSTRAINT ck_pipeline_progress_stage
        CHECK (stage IN ('LOAD', 'GENERATE')),
    CONSTRAINT ck_pipeline_progress_processed_count
        CHECK (processed_count >= 0)
);

COMMENT ON TABLE  batch.pipeline_progress IS
    'ADR-038 平台 worker 续跑位点:Import LOAD / Export GENERATE 阶段内进度持久化';
COMMENT ON COLUMN batch.pipeline_progress.pipeline_instance_id IS
    '关联 batch.pipeline_instance.id;一次 pipeline 实例同 stage 只有一行';
COMMENT ON COLUMN batch.pipeline_progress.stage           IS
    'LOAD(Import) / GENERATE(Export);Atomic / Dispatch / Process 不在此续跑';
COMMENT ON COLUMN batch.pipeline_progress.position_marker IS
    'Import=已处理到的行号(字符串化);Export=plugin 的 nextCursor 序列化';
COMMENT ON COLUMN batch.pipeline_progress.processed_count IS
    '已成功处理记录数(chunk/page 提交时累加,与 position_marker 同事务推进)';
COMMENT ON COLUMN batch.pipeline_progress.completed       IS
    'true 表示该 stage 在本 pipeline 实例已整体完成(幂等跳过)';

-- 启动续跑主路径:按 (tenant_id, pipeline_instance_id) 加载所有 stage 位点
CREATE INDEX IF NOT EXISTS idx_pipeline_progress_tenant_instance
    ON batch.pipeline_progress (tenant_id, pipeline_instance_id);

-- 已完成位点的清理 / 归档查询(可选,T+N 归档时按 completed_at 范围筛)
CREATE INDEX IF NOT EXISTS idx_pipeline_progress_completed_at
    ON batch.pipeline_progress (completed_at)
    WHERE completed = TRUE;

-- ---------------------------------------------------------
-- archive 镜像(CLAUDE.md 红线;LIKE INCLUDING ALL 与 V159 一致)
-- ---------------------------------------------------------
CREATE TABLE IF NOT EXISTS archive.pipeline_progress_archive
    (LIKE batch.pipeline_progress INCLUDING DEFAULTS INCLUDING GENERATED INCLUDING IDENTITY INCLUDING CONSTRAINTS);

-- LIKE INCLUDING CONSTRAINTS 不复制 PRIMARY KEY(归档 UPSERT ON CONFLICT(id) 依赖,见 V140 / V159 同款补 PK)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        JOIN pg_namespace n ON n.oid = t.relnamespace
        WHERE n.nspname = 'archive'
          AND t.relname = 'pipeline_progress_archive'
          AND c.contype = 'p'
    ) THEN
        ALTER TABLE archive.pipeline_progress_archive
            ADD CONSTRAINT pk_pipeline_progress_archive PRIMARY KEY (id);
    END IF;
END $$;

COMMENT ON TABLE archive.pipeline_progress_archive IS
    'V164 archive mirror of batch.pipeline_progress';
