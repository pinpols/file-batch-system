-- =========================================================
-- V75 - 创建 PROCESS 流水线 WAP（写入-审计-发布）暂存表
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
    'PROCESS 的 WAP 流水线暂存区；COMPUTE 写入数据，VALIDATE 校验，COMMIT 发布，FEEDBACK 清理。';
COMMENT ON COLUMN batch.process_staging.batch_key IS
    'PREPARE 阶段为每个任务生成的唯一键（taskId 加 UUID 后缀），用于关联一次 PROCESS 运行的所有行。';
COMMENT ON COLUMN batch.process_staging.payload IS
    '按目标列名组织的 JSONB 行数据；COMMIT 阶段通过 jsonb_populate_record(NULL::<target>, payload) 还原。';
