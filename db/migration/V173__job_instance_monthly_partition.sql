-- V173: job_instance 转 biz_date 月分区。8 条子表 FK 转应用层守护
-- (SuccessInstanceArchiveScheduler 已支持级联删)。
-- biz_date 升 NOT NULL(INSERT COALESCE(biz_date, created_at::date) 兜底,实库 0 行 NULL)。
-- 唯一约束语义变化:dedup/instance_no 唯一从全局弱化为每 biz_date 内(评审已知悉)。
-- 列/约束/索引权威源:scripts/db/partition-migration/02-*.sql(2026-06-10 pg_dump 重生成)。
-- ⚠️ UNIQUE 约束名沿用原表名(不用 _p_*):DefaultLaunchService:531 靠 m.contains(
--    "uk_job_instance_tenant_dedup") 判 dedup 幂等兜底,改名会让分区后兜底静默失效。

-- A) 建分区父表(列集 = pg_dump 实库 DDL,2026-06-10;biz_date 升 NOT NULL,
--    INSERT 时 COALESCE(biz_date, created_at::date) 兜底——分区键不可为 NULL)
CREATE TABLE batch.job_instance_p (
    id                        BIGINT       NOT NULL,
    tenant_id                 VARCHAR(64)  NOT NULL,
    job_definition_id         BIGINT       NOT NULL,
    trigger_request_id        BIGINT,
    job_code                  VARCHAR(128) NOT NULL,
    instance_no               VARCHAR(128) NOT NULL,
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
    operator_id               VARCHAR(64),
    rerun_flag                BOOLEAN      NOT NULL DEFAULT false,
    retry_flag                BOOLEAN      NOT NULL DEFAULT false,
    rerun_reason              VARCHAR(512),
    related_file_id           BIGINT,
    parent_instance_id        BIGINT,
    result_summary            JSONB,
    run_attempt               INTEGER      NOT NULL DEFAULT 1,
    high_water_mark_in        VARCHAR(64),
    high_water_mark_out       VARCHAR(64),
    calendar_code             VARCHAR(64),
    data_interval_start       TIMESTAMPTZ,
    data_interval_end         TIMESTAMPTZ,
    job_definition_version    INTEGER,
    rerun_policy_snapshot     JSONB        NOT NULL DEFAULT '{}'::jsonb,
    replay_session_id         BIGINT,
    failure_class             VARCHAR(32),
    dry_run                   BOOLEAN      NOT NULL DEFAULT false,
    -- PK 必须包含分区键
    CONSTRAINT job_instance_p_pkey PRIMARY KEY (id, biz_date),
    -- 唯一约束也需包含分区键(语义弱化见文件头注释)
    CONSTRAINT uk_job_instance_p_dedup UNIQUE (tenant_id, dedup_key, run_attempt, biz_date),
    CONSTRAINT uk_job_instance_p_instance_no UNIQUE (tenant_id, instance_no, biz_date),
    -- CHECK 全量对齐实库(constraint 名 per-table,可与 legacy 同名共存)
    CONSTRAINT ck_job_instance_data_interval_pair CHECK (
        (data_interval_start IS NULL AND data_interval_end IS NULL)
        OR (data_interval_start IS NOT NULL AND data_interval_end IS NOT NULL
            AND data_interval_start < data_interval_end)),
    CONSTRAINT ck_job_instance_expected_duration_seconds CHECK (expected_duration_seconds >= 0),
    CONSTRAINT ck_job_instance_expected_partition_count CHECK (expected_partition_count >= 0),
    CONSTRAINT ck_job_instance_failed_partition_count CHECK (failed_partition_count >= 0),
    CONSTRAINT ck_job_instance_failure_class CHECK (
        failure_class IS NULL OR failure_class IN
        ('INFRASTRUCTURE','DATA_QUALITY','BUSINESS_RULE','CONFIG','UPSTREAM_DELAY','TIMEOUT','UNKNOWN')),
    CONSTRAINT ck_job_instance_priority CHECK (priority >= 1 AND priority <= 9),
    CONSTRAINT ck_job_instance_run_attempt CHECK (run_attempt >= 1),
    CONSTRAINT ck_job_instance_status CHECK (instance_status IN
        ('CREATED','WAITING','READY','RUNNING','PARTIAL_FAILED','SUCCESS','FAILED',
         'CANCELLED','TERMINATED','SUCCESS_DRY_RUN','FAILED_DRY_RUN')),
    CONSTRAINT ck_job_instance_success_partition_count CHECK (success_partition_count >= 0),
    CONSTRAINT ck_job_instance_trigger_source CHECK (
        trigger_request_id IS NOT NULL OR trigger_type = 'MANUAL'),
    CONSTRAINT ck_job_instance_trigger_type CHECK (trigger_type IN
        ('SCHEDULED','API','MANUAL','EVENT','CATCH_UP','RERUN'))
) PARTITION BY RANGE (biz_date);

ALTER TABLE batch.job_instance_p
    ALTER COLUMN id SET DEFAULT nextval('batch.job_instance_id_seq'::regclass);

-- 出向 FK(分区表 → 普通表,PG 12+ 支持)对齐实库
ALTER TABLE batch.job_instance_p
    ADD CONSTRAINT job_instance_p_job_definition_id_fkey
    FOREIGN KEY (job_definition_id) REFERENCES batch.job_definition(id);
ALTER TABLE batch.job_instance_p
    ADD CONSTRAINT job_instance_p_trigger_request_id_fkey
    FOREIGN KEY (trigger_request_id) REFERENCES batch.trigger_request(id);

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

-- C) 业务查询索引(对齐实库全部 12 个二级索引;_p 命名与 legacy 共存)
CREATE INDEX idx_job_instance_p_status ON batch.job_instance_p (tenant_id, instance_status, biz_date);
CREATE INDEX idx_job_instance_p_job_code ON batch.job_instance_p (tenant_id, job_code, biz_date);
CREATE INDEX idx_job_instance_p_finished_at ON batch.job_instance_p (finished_at)
    WHERE finished_at IS NOT NULL;
-- DBA-2026-05-20 P1-1 — 活跃实例 partial, scheduler/wait 队列扫描专用
CREATE INDEX idx_job_instance_p_active_tenant_created ON batch.job_instance_p (tenant_id, created_at)
    WHERE instance_status IN ('CREATED','WAITING','READY','RUNNING');
CREATE INDEX idx_job_instance_p_batch_lookup ON batch.job_instance_p (tenant_id, job_code, biz_date, batch_no);
CREATE INDEX idx_job_instance_p_calendar_bizdate ON batch.job_instance_p (tenant_id, calendar_code, biz_date)
    WHERE calendar_code IS NOT NULL;
CREATE INDEX idx_job_instance_p_created_at ON batch.job_instance_p (created_at);
CREATE INDEX idx_job_instance_p_failure_class ON batch.job_instance_p (tenant_id, failure_class, finished_at DESC)
    WHERE failure_class IS NOT NULL;
CREATE INDEX idx_job_instance_p_job_status ON batch.job_instance_p (tenant_id, job_code, instance_status);
CREATE INDEX idx_job_instance_p_related_file ON batch.job_instance_p (tenant_id, related_file_id);
CREATE INDEX idx_job_instance_p_sla_tracking ON batch.job_instance_p (instance_status, deadline_at, sla_alerted_at);
-- DBA-2026-05-20 P0-2 / P1-1 — cleanup-success-instances.sql 与 console biz_date 列表 ORDER BY DESC
CREATE INDEX idx_job_instance_p_tenant_bizdate_status ON batch.job_instance_p (tenant_id, biz_date DESC, instance_status);
CREATE INDEX idx_job_instance_p_tenant_status_started ON batch.job_instance_p (tenant_id, instance_status, started_at DESC);
CREATE INDEX idx_job_instance_p_trace_id ON batch.job_instance_p (trace_id);

-- D) 复制数据(显式全列;biz_date NULL 用 created_at::date 兜底)
INSERT INTO batch.job_instance_p (
    id, tenant_id, job_definition_id, trigger_request_id, job_code, instance_no,
    biz_date, trigger_type, instance_status, queue_code, worker_group, priority,
    dedup_key, version, expected_partition_count, success_partition_count,
    failed_partition_count, trace_id, params_snapshot, started_at, finished_at,
    created_at, updated_at, deadline_at, expected_duration_seconds, sla_alerted_at,
    batch_no, operator_id, rerun_flag, retry_flag, rerun_reason, related_file_id,
    parent_instance_id, result_summary, run_attempt, high_water_mark_in,
    high_water_mark_out, calendar_code, data_interval_start, data_interval_end,
    job_definition_version, rerun_policy_snapshot, replay_session_id, failure_class, dry_run
)
SELECT
    id, tenant_id, job_definition_id, trigger_request_id, job_code, instance_no,
    COALESCE(biz_date, created_at::date), trigger_type, instance_status, queue_code,
    worker_group, priority, dedup_key, version, expected_partition_count,
    success_partition_count, failed_partition_count, trace_id, params_snapshot,
    started_at, finished_at, created_at, updated_at, deadline_at,
    expected_duration_seconds, sla_alerted_at, batch_no, operator_id, rerun_flag,
    retry_flag, rerun_reason, related_file_id, parent_instance_id, result_summary,
    run_attempt, high_water_mark_in, high_water_mark_out, calendar_code,
    data_interval_start, data_interval_end, job_definition_version,
    rerun_policy_snapshot, replay_session_id, failure_class, dry_run
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

-- 修复序列 ownership（RENAME 不转移 SEQUENCE OWNED BY，直接 DROP legacy 会级联删序列）
ALTER SEQUENCE batch.job_instance_id_seq OWNED BY batch.job_instance.id;
DROP TABLE batch.job_instance_legacy;

-- legacy 已 DROP 释放原约束名,改回兼容名(DefaultLaunchService:531 按 contains
-- "uk_job_instance_tenant_dedup" 判 dedup 幂等;建表期用 _p_* 仅为避开与 legacy 撞名)。
ALTER TABLE batch.job_instance RENAME CONSTRAINT uk_job_instance_p_dedup TO uk_job_instance_tenant_dedup_attempt;
ALTER TABLE batch.job_instance RENAME CONSTRAINT uk_job_instance_p_instance_no TO uk_job_instance_tenant_instance_no;
