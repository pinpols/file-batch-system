-- =========================================================
-- 02-job-instance-partitioned.sql
-- job_instance 改造为按 biz_date 月分区。配合 01-outbox-event-partitioned.sql 使用。
--
-- 为什么用 biz_date 而不是 created_at：
--   - 业务查询多按 biz_date 过滤（"昨天/上周的批次"），分区裁剪命中率高
--   - 跨年/跨月 catch-up 时同一日期的实例归到对应月分区，便于按业务窗口归档
--   - created_at 也可用作分区键（适合纯运行态查询），按业务画像取舍
--
-- 重要前置（**比 outbox 更复杂**）：
--   - PK 改为 (id, biz_date)，所有外键引用 job_instance.id 的表都要重检
--   - 8 个 FK 子表：job_task / job_partition / job_step_instance / pipeline_instance /
--     workflow_run / job_execution_log / compensation_command / job_instance.parent_instance_id（自引用）
--   - 子表 FK 跨分区不被 PG 原生支持 → 全部改为应用层守护
--   - cleanup-success-instances.sql / SuccessInstanceArchiveScheduler 已支持级联删，仍可用
--
-- 风险：HIGH。**强烈建议先在 staging 完整跑一遍**，包括所有 e2e 测试。
-- =========================================================

\set ON_ERROR_STOP on

BEGIN;

-- A) 建分区父表（biz_date 不可为 NULL，否则分区裁剪失败）
CREATE TABLE batch.job_instance_p (
    id                        BIGINT       NOT NULL,
    tenant_id                 VARCHAR(64)  NOT NULL,
    job_definition_id         BIGINT       NOT NULL,
    trigger_request_id        BIGINT,
    job_code                  VARCHAR(128) NOT NULL,
    instance_no               VARCHAR(128) NOT NULL,
    -- biz_date NOT NULL（原表允许 NULL，需要补默认值；下面 INSERT 时用 created_at::date 兜底）
    biz_date                  DATE         NOT NULL,
    trigger_type              VARCHAR(32)  NOT NULL,
    instance_status           VARCHAR(32)  NOT NULL,
    queue_code                VARCHAR(128),
    worker_group              VARCHAR(128),
    priority                  INTEGER      NOT NULL DEFAULT 5,
    dedup_key                 VARCHAR(256) NOT NULL,
    version                   BIGINT       NOT NULL DEFAULT 0,
    expected_partition_count  INTEGER      NOT NULL DEFAULT 0,
    success_partition_count   INTEGER      NOT NULL DEFAULT 0,
    failed_partition_count    INTEGER      NOT NULL DEFAULT 0,
    trace_id                  VARCHAR(128),
    params_snapshot           JSONB,
    started_at                TIMESTAMPTZ,
    finished_at               TIMESTAMPTZ,
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deadline_at               TIMESTAMPTZ,
    expected_duration_seconds INTEGER      NOT NULL DEFAULT 0,
    sla_alerted_at            TIMESTAMPTZ,
    batch_no                  VARCHAR(128),
    parent_instance_id        BIGINT,
    run_attempt               INTEGER      NOT NULL DEFAULT 1,
    -- PK 必须包含分区键
    CONSTRAINT job_instance_p_pkey PRIMARY KEY (id, biz_date),
    -- 唯一约束（含 run_attempt 与 dedup_key）也需包含分区键
    CONSTRAINT uk_job_instance_p_dedup UNIQUE (tenant_id, dedup_key, run_attempt, biz_date),
    CONSTRAINT ck_job_instance_p_run_attempt CHECK (run_attempt >= 1)
) PARTITION BY RANGE (biz_date);

ALTER TABLE batch.job_instance_p
    ALTER COLUMN id SET DEFAULT nextval('batch.job_instance_id_seq'::regclass);

-- B) 建月分区（同 outbox-event 模式，前 24 月 + 后 12 月 + 默认）
DO $$
DECLARE
    m INT;
    start_month DATE;
    end_month DATE;
    pname TEXT;
BEGIN
    FOR m IN -23..12 LOOP
        start_month := date_trunc('month', CURRENT_DATE) + (m || ' months')::interval;
        end_month := start_month + interval '1 month';
        pname := 'job_instance_p_' || to_char(start_month, 'YYYY_MM');
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS batch.%I PARTITION OF batch.job_instance_p '
            'FOR VALUES FROM (%L) TO (%L)',
            pname, start_month, end_month);
    END LOOP;
    EXECUTE 'CREATE TABLE IF NOT EXISTS batch.job_instance_p_default '
            'PARTITION OF batch.job_instance_p DEFAULT';
END$$;

-- C) 业务查询索引
CREATE INDEX idx_job_instance_p_status ON batch.job_instance_p (tenant_id, instance_status, biz_date);
CREATE INDEX idx_job_instance_p_job_code ON batch.job_instance_p (tenant_id, job_code, biz_date);
CREATE INDEX idx_job_instance_p_finished_at ON batch.job_instance_p (finished_at)
    WHERE finished_at IS NOT NULL;

-- D) 复制数据；biz_date NULL 用 created_at::date 兜底
INSERT INTO batch.job_instance_p
SELECT
    id, tenant_id, job_definition_id, trigger_request_id, job_code, instance_no,
    COALESCE(biz_date, created_at::date) AS biz_date,
    trigger_type, instance_status, queue_code, worker_group, priority, dedup_key,
    version, expected_partition_count, success_partition_count, failed_partition_count,
    trace_id, params_snapshot, started_at, finished_at, created_at, updated_at,
    deadline_at, expected_duration_seconds, sla_alerted_at, batch_no, parent_instance_id,
    run_attempt
FROM batch.job_instance;

SELECT setval('batch.job_instance_id_seq',
              COALESCE((SELECT MAX(id) FROM batch.job_instance_p), 1));

-- E) 解所有 FK（自引用 + 8 张子表）
ALTER TABLE batch.job_task DROP CONSTRAINT IF EXISTS job_task_job_instance_id_fkey;
ALTER TABLE batch.job_partition DROP CONSTRAINT IF EXISTS job_partition_job_instance_id_fkey;
ALTER TABLE batch.job_step_instance DROP CONSTRAINT IF EXISTS job_step_instance_job_instance_id_fkey;
ALTER TABLE batch.pipeline_instance DROP CONSTRAINT IF EXISTS pipeline_instance_related_job_instance_id_fkey;
ALTER TABLE batch.workflow_run DROP CONSTRAINT IF EXISTS workflow_run_related_job_instance_id_fkey;
ALTER TABLE batch.job_execution_log DROP CONSTRAINT IF EXISTS job_execution_log_job_instance_id_fkey;
ALTER TABLE batch.compensation_command DROP CONSTRAINT IF EXISTS compensation_command_related_job_instance_id_fkey;
ALTER TABLE batch.job_instance DROP CONSTRAINT IF EXISTS job_instance_parent_instance_id_fkey;

-- F) 切换名字
ALTER TABLE batch.job_instance RENAME TO job_instance_legacy;
ALTER TABLE batch.job_instance_p RENAME TO job_instance;

-- 不重建跨分区 FK（PG 原生不支持）；应用层 SuccessInstanceArchiveScheduler 已带级联删

COMMIT;

\echo '=== 分区分布 ==='
SELECT
    inhrelid::regclass AS partition,
    pg_size_pretty(pg_relation_size(inhrelid)) AS size
FROM pg_inherits
WHERE inhparent = 'batch.job_instance'::regclass
ORDER BY partition
LIMIT 20;

\echo '=== 总行数 vs legacy ==='
SELECT
    (SELECT count(*) FROM batch.job_instance) AS new_count,
    (SELECT count(*) FROM batch.job_instance_legacy) AS legacy_count;

\echo '=== 验证后请手动 DROP TABLE batch.job_instance_legacy CASCADE; ==='
