-- =========================================================
-- batch_business 库 batch.process_staging 表
-- =========================================================
-- worker-process 走 business datasource(application.yml batch.datasource.business),
-- PROCESS WAP 流水线 staging 行必须跟 biz.<target> 同库才能 INSERT staging + COMMIT
-- target 在一个事务里。
-- 之前 V75 把表建到了 platform 库,worker-process 走 business 连接查 "batch.process_staging
-- does not exist"。这里在 business 库一并建表 + 索引。
--
-- 完整 biz schema(customer_account / loan_repayment / loan_charge_off 等业务表)走
-- scripts/db/business/create_biz_tables.sql,不在 init 脚本里跑(那是测试 fixture 数据
-- 的来源,生产 prod 走自己的 schema 治理路径)。PROCESS staging-generation 的基础表结构
-- 必须与该脚本保持一致；本 init 先创建它，后续业务库 bootstrap 再补齐 RLS 和 OPEN-generation
-- 写入守护。否则 CREATE TABLE IF NOT EXISTS 会保留旧分区表，掩盖缺列并让后续 RLS/索引失败。
-- =========================================================

\connect batch_business

CREATE SCHEMA IF NOT EXISTS batch;
COMMENT ON SCHEMA batch IS '平台共享 schema，在业务库中复用以存放跨库 staging 表。';

CREATE TABLE IF NOT EXISTS batch.process_staging_generation (
    generation_id      UUID PRIMARY KEY,
    tenant_id          TEXT NOT NULL,
    job_task_id        BIGINT,
    batch_key          TEXT NOT NULL,
    generation_status  TEXT NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    sealed_at          TIMESTAMPTZ,
    CONSTRAINT ck_process_staging_generation_status
        CHECK (generation_status IN ('OPEN', 'SEALED', 'RECLAIMED')),
    CONSTRAINT ck_process_staging_generation_sealed_at
        CHECK ((generation_status = 'OPEN' AND sealed_at IS NULL)
            OR (generation_status IN ('SEALED', 'RECLAIMED') AND sealed_at IS NOT NULL)),
    CONSTRAINT uk_process_staging_generation_identity
        UNIQUE (tenant_id, generation_id, batch_key)
);

CREATE INDEX IF NOT EXISTS idx_process_staging_generation_tenant_task
    ON batch.process_staging_generation (tenant_id, job_task_id, created_at);

-- 按 staged_at 天级 RANGE 分区(与 scripts/db/business/create_biz_tables.sql 对齐):
-- staging 行批次写满即清,但 DELETE 不缩文件,长期高水位会把堆膨胀到历史最大暂存量再不回收
-- (实测超过磁盘)。分区后由 ProcessStagingOrphanCleaner 预建未来日分区 + DROP 过期日分区,
-- DROP 瞬间把空间还给 OS。分区键 staged_at 必须进 PK(PG 声明式分区约束)。
CREATE TABLE IF NOT EXISTS batch.process_staging (
    batch_key      TEXT        NOT NULL,
    staging_generation_id UUID NOT NULL,
    row_seq        BIGSERIAL   NOT NULL,
    tenant_id      TEXT        NOT NULL,
    target_schema  TEXT        NOT NULL,
    target_table   TEXT        NOT NULL,
    payload        JSONB       NOT NULL,
    staged_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (batch_key, staging_generation_id, row_seq, staged_at),
    CONSTRAINT fk_process_staging_generation_identity
        FOREIGN KEY (tenant_id, staging_generation_id, batch_key)
        REFERENCES batch.process_staging_generation (tenant_id, generation_id, batch_key)
        ON DELETE NO ACTION
) PARTITION BY RANGE (staged_at);

-- 回退分区:维护调度滞后时写入落到 default,绝不因缺分区 INSERT 失败。
CREATE TABLE IF NOT EXISTS batch.process_staging_default
    PARTITION OF batch.process_staging DEFAULT;

-- 父表建索引 → PG 自动在所有现有 + 未来分区上同名建索引。
CREATE INDEX IF NOT EXISTS idx_process_staging_batch_key
    ON batch.process_staging (batch_key);
CREATE INDEX IF NOT EXISTS idx_process_staging_batch_generation
    ON batch.process_staging (batch_key, staging_generation_id);
CREATE INDEX IF NOT EXISTS idx_process_staging_tenant_batch
    ON batch.process_staging (tenant_id, batch_key);
CREATE INDEX IF NOT EXISTS idx_process_staging_tenant_generation
    ON batch.process_staging (tenant_id, staging_generation_id);
CREATE INDEX IF NOT EXISTS idx_process_staging_target_batch
    ON batch.process_staging (tenant_id, target_schema, target_table, batch_key, staging_generation_id);
CREATE INDEX IF NOT EXISTS idx_process_staging_staged_at
    ON batch.process_staging (staged_at);

-- 高翻台暂存表 autovacuum 调参作用于各分区(分区是独立物理表,参数不从父表继承)。
-- default 分区在此设;日分区由 ProcessStagingOrphanCleaner 建分区时一并 SET。
-- 只调触发频率,不动 cost_delay/cost_limit(避免表级 IO 节流影响整库)。幂等可重跑。
ALTER TABLE batch.process_staging_default SET (
    autovacuum_vacuum_scale_factor        = 0.05,
    autovacuum_vacuum_threshold           = 1000,
    autovacuum_vacuum_insert_scale_factor = 0.05,
    autovacuum_analyze_scale_factor       = 0.05
);

COMMENT ON TABLE batch.process_staging IS
    'PROCESS WAP 流水线暂存区；数据在 COMPUTE 阶段写入、VALIDATE 阶段校验、COMMIT 阶段发布、FEEDBACK 阶段清理。该表位于业务库，以便与 biz.<target> 共用事务。';
