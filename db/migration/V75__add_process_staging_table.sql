-- =========================================================
-- V75 - PROCESS pipeline staging table for WAP (Write-Audit-Publish)
-- =========================================================
-- 设计依据:docs/design/batch-classification-and-gaps.md §4.5。
-- PROCESS 任务的 5 stage 流水线 PREPARE → COMPUTE → VALIDATE → COMMIT → FEEDBACK
-- 在 COMPUTE 阶段把待写行先 INSERT 到本表(以 batch_key 标记),VALIDATE 阶段对
-- staging 行跑数据质量规则,COMMIT 阶段用 jsonb_populate_record 把 staging payload
-- 反序列化为目标表行做原子写入,FEEDBACK 阶段清理 staging。
--
-- 共享一张表 + JSONB payload 是为了让框架不依赖每个目标表手工建对应 staging 表。
-- payload 的 JSONB key 对应目标表列名(由 sqlTransformCompute spec 的 columns 映射
-- 决定),COMMIT 用 jsonb_populate_record(NULL::biz.target, payload) 还原类型。

CREATE TABLE IF NOT EXISTS batch.process_staging (
    batch_key      TEXT        NOT NULL,
    row_seq        BIGSERIAL   NOT NULL,
    tenant_id      TEXT        NOT NULL,
    target_schema  TEXT        NOT NULL,
    target_table   TEXT        NOT NULL,
    payload        JSONB       NOT NULL,
    staged_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (batch_key, row_seq)
);

-- batch_key 单独索引:VALIDATE / COMMIT / FEEDBACK 三阶段都按 batch_key 过滤。
CREATE INDEX IF NOT EXISTS idx_process_staging_batch_key
    ON batch.process_staging (batch_key);

-- staged_at 索引:运维清理孤儿 staging 行(任务挂了没走到 FEEDBACK 阶段)用。
CREATE INDEX IF NOT EXISTS idx_process_staging_staged_at
    ON batch.process_staging (staged_at);

COMMENT ON TABLE batch.process_staging IS
    'PROCESS WAP pipeline staging area; rows written at COMPUTE, validated at VALIDATE, published at COMMIT, cleaned at FEEDBACK.';
COMMENT ON COLUMN batch.process_staging.batch_key IS
    'Per-task unique key generated at PREPARE (taskId + uuid suffix); ties together rows of one PROCESS run.';
COMMENT ON COLUMN batch.process_staging.payload IS
    'JSONB row body keyed by target column name; restored at COMMIT via jsonb_populate_record(NULL::<target>, payload).';
