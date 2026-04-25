-- =========================================================
-- 01-outbox-event-partitioned.sql
-- outbox_event 改造为按 created_at 月分区。配合 02-job-instance-partitioned.sql 使用。
--
-- 前置：必须在维护窗口（业务低峰，最好停机或只读）执行；带数据迁移，不可在线 ALTER。
--
-- 步骤：
--   A) 建分区父表 outbox_event_p（同结构，PK 含 created_at），按月范围分区
--   B) 建近 24 个月 + 默认分区
--   C) 把 outbox_event 数据 INSERT INTO outbox_event_p
--   D) 改名：outbox_event → outbox_event_legacy；outbox_event_p → outbox_event
--   E) 重建 event_delivery_log FK；重建索引
--   F) 验证后 DROP outbox_event_legacy
--
-- 性能与回滚：步骤 C 是大复制（百万行级），用 INSERT INTO ... SELECT 单事务即可；如果太慢用
-- pg_dump | pg_restore 双库导。**步骤 D 后不可滚回**——只能从 legacy 表重建。
-- =========================================================

\set ON_ERROR_STOP on

BEGIN;

-- A) 建分区父表
CREATE TABLE batch.outbox_event_p (
    id              BIGINT       NOT NULL,
    tenant_id       VARCHAR(64)  NOT NULL,
    aggregate_type  VARCHAR(64)  NOT NULL,
    aggregate_id    BIGINT       NOT NULL,
    event_type      VARCHAR(64)  NOT NULL,
    event_key       VARCHAR(256) NOT NULL,
    payload_json    JSONB        NOT NULL,
    publish_status  VARCHAR(32)  NOT NULL DEFAULT 'NEW',
    publish_attempt INTEGER      NOT NULL DEFAULT 0,
    next_publish_at TIMESTAMPTZ,
    trace_id        VARCHAR(128),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- PK 必须包含分区键（PG 限制）
    CONSTRAINT outbox_event_p_pkey PRIMARY KEY (id, created_at),
    -- 唯一约束同样必须包含分区键
    CONSTRAINT uk_outbox_event_p_key UNIQUE (tenant_id, event_key, created_at),
    CONSTRAINT ck_outbox_p_publish_status CHECK (
        publish_status IN ('NEW','PUBLISHING','PUBLISHED','FAILED','GIVE_UP')
    ),
    CONSTRAINT ck_outbox_p_publish_attempt CHECK (publish_attempt >= 0)
) PARTITION BY RANGE (created_at);

-- 复制 sequence（避免 id 冲突）
ALTER TABLE batch.outbox_event_p
    ALTER COLUMN id SET DEFAULT nextval('batch.outbox_event_id_seq'::regclass);

-- B) 建近 24 个月 + 默认分区。生产建议用 pg_partman 自动化建/删分区
DO $$
DECLARE
    m INT;
    start_month DATE;
    end_month DATE;
    pname TEXT;
BEGIN
    -- 当前月作为基准，建 [now-23, now+12]，共 36 个月分区
    FOR m IN -23..12 LOOP
        start_month := date_trunc('month', CURRENT_DATE) + (m || ' months')::interval;
        end_month := start_month + interval '1 month';
        pname := 'outbox_event_p_' || to_char(start_month, 'YYYY_MM');
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS batch.%I PARTITION OF batch.outbox_event_p '
            'FOR VALUES FROM (%L) TO (%L)',
            pname, start_month, end_month);
    END LOOP;
    -- DEFAULT 分区兜底未来超期数据；运维应保证 cron 提前建月分区，不让 DEFAULT 接负担
    EXECUTE 'CREATE TABLE IF NOT EXISTS batch.outbox_event_p_default '
            'PARTITION OF batch.outbox_event_p DEFAULT';
END$$;

-- C) 索引（每个分区单独建；PG 14+ 支持父表上 CREATE INDEX 自动级联到所有分区）
CREATE INDEX idx_outbox_p_aggregate ON batch.outbox_event_p (aggregate_type, aggregate_id);
CREATE INDEX idx_outbox_p_publish_status
    ON batch.outbox_event_p (publish_status, next_publish_at);

-- D) 复制数据
INSERT INTO batch.outbox_event_p (
    id, tenant_id, aggregate_type, aggregate_id, event_type, event_key, payload_json,
    publish_status, publish_attempt, next_publish_at, trace_id, created_at, updated_at
)
SELECT
    id, tenant_id, aggregate_type, aggregate_id, event_type, event_key, payload_json,
    publish_status, publish_attempt, next_publish_at, trace_id, created_at, updated_at
FROM batch.outbox_event;

-- 同步 sequence 到当前最大 id
SELECT setval('batch.outbox_event_id_seq', COALESCE((SELECT MAX(id) FROM batch.outbox_event_p), 1));

-- E) 切换名字（先解 event_delivery_log FK，因为它指向旧 outbox_event）
ALTER TABLE batch.event_delivery_log
    DROP CONSTRAINT IF EXISTS event_delivery_log_outbox_event_id_fkey;

ALTER TABLE batch.outbox_event RENAME TO outbox_event_legacy;
ALTER TABLE batch.outbox_event_p RENAME TO outbox_event;

-- 重建 FK（注意：PG 不允许跨分区表 FK；这里维持原 FK 形态需要 partition pruning 路径）
-- 由于分区键是 created_at，event_delivery_log 不带 created_at 列，做不到 native FK；
-- 改为应用层守护：cleanup-outbox-events.sql 已有级联删 event_delivery_log 步骤
-- 不重建 FK，留 doc 注释提醒。

COMMIT;

-- F) 验证（不在事务里）
\echo '=== 分区分布 ==='
SELECT
    inhrelid::regclass AS partition,
    pg_size_pretty(pg_relation_size(inhrelid)) AS size
FROM pg_inherits
WHERE inhparent = 'batch.outbox_event'::regclass
ORDER BY partition;

\echo '=== 总行数 vs legacy 行数（应一致） ==='
SELECT
    (SELECT count(*) FROM batch.outbox_event) AS new_count,
    (SELECT count(*) FROM batch.outbox_event_legacy) AS legacy_count;

\echo '=== 验证后请手动 DROP TABLE batch.outbox_event_legacy CASCADE; ==='
