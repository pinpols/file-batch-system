-- =========================================================
-- 01-outbox-event-partitioned.sql
-- outbox_event 改造为按 created_at 月分区。配合 02-job-instance-partitioned.sql 使用。
--
-- 🔴 2026-06-10 实测硬阻塞(本地全链路实跑发现,已回滚):**本脚本当前禁止执行**。
--    分区化迫使 UNIQUE(tenant_id,event_key) → UNIQUE(tenant_id,event_key,created_at),
--    而 OutboxEventMapper.xml 的幂等写依赖旧约束:
--      - `on conflict (tenant_id, event_key) do nothing`(insert 主路径)→ 运行时报
--        "no unique or exclusion constraint matching the ON CONFLICT specification",
--        orchestrator 所有 outbox 写入失败,主链 DB→Outbox→Kafka 对新事件中断
--      - `on conflict (id) do nothing` 同样失配(PK 已变 (id,created_at))
--    且语义上分区表**无法表达全局 (tenant_id,event_key) 唯一**——事件幂等契约被弱化,
--    不是改 SQL 列就完事。执行前必须完成应用层改造 + 评审幂等语义:
--    受影响 mapper(grep 'on conflict' 实measured 2026-06-10):OutboxEventMapper.xml(4处)/
--    JobInstanceMapper.xml / WorkflowRunMapper.xml(2处) / SuccessInstanceArchiveMapper.xml /
--    ConsolePushJobNotificationMapper.xml / PlatformFileRuntimeMapper.xml(2处,逐一核对目标表)。
--    另:event_outbox_retry 也 FK 引用 outbox_event(id),F 步骤 DROP legacy CASCADE 会
--    连带删它的 FK——执行时需同步处理。
--    结论:此迁移是【应用契约级变更】,不是运维改造;详见
--    docs/backlog/citus-introduction-plan-2026-06-06.md §0.5 2026-06-10 复核。
--
-- 前置：必须在维护窗口（业务低峰，最好停机或只读）执行；带数据迁移，不可在线 ALTER。
--
-- ⚠️ 2026-06-10 重生成:首版脚本按写作时 schema 手写,"备而未用"期间 Flyway 演进新增了
--    priority 列(V88)+ 3 个二级索引,首次实际应用被 ArchiveSchemaDriftCheck fail-fast
--    拦截(新表缺列 → orchestrator 拒启)。本版列/约束/索引全部从 pg_dump 实库 DDL 重生成。
--    教训:本脚本不在 CI/测试覆盖内,每次实际执行前先 pg_dump 对照列集是否漂移。
--
-- 步骤：
--   A) 建分区父表 outbox_event_p（同结构，PK 含 created_at），按月范围分区
--   B) 建近 24 个月 + 未来 12 个月 + 默认分区
--   C) 业务查询索引(_p 命名,与 legacy 共存期不冲突)
--   D) 把 outbox_event 数据 INSERT INTO outbox_event_p
--   E) 解 event_delivery_log FK + 改名：outbox_event → outbox_event_legacy；outbox_event_p → outbox_event
--   F) 验证后手动 DROP outbox_event_legacy
--
-- 性能与回滚：步骤 D 是大复制（百万行级），用 INSERT INTO ... SELECT 单事务即可；如果太慢用
-- pg_dump | pg_restore 双库导。**步骤 E 改名后回滚 = 反向改名 + DROP 分区表**（legacy 在即可）。
-- =========================================================

\set ON_ERROR_STOP on

BEGIN;

-- A) 建分区父表(列集 = pg_dump 实库 DDL,2026-06-10)
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
    -- V88 (2026-05-03): 拷自 source job_definition.priority,OutboxPollScheduler 按此 desc 排序
    priority        INTEGER      NOT NULL DEFAULT 5,
    -- PK 必须包含分区键（PG 限制）
    CONSTRAINT outbox_event_p_pkey PRIMARY KEY (id, created_at),
    -- 唯一约束同样必须包含分区键
    CONSTRAINT uk_outbox_event_p_key UNIQUE (tenant_id, event_key, created_at),
    CONSTRAINT ck_outbox_p_publish_status CHECK (
        publish_status IN ('NEW','PUBLISHING','PUBLISHED','FAILED','GIVE_UP')
    ),
    CONSTRAINT ck_outbox_p_publish_attempt CHECK (publish_attempt >= 0),
    CONSTRAINT ck_outbox_p_priority CHECK (priority >= 0 AND priority <= 10)
) PARTITION BY RANGE (created_at);

-- 复制 sequence（避免 id 冲突）
ALTER TABLE batch.outbox_event_p
    ALTER COLUMN id SET DEFAULT nextval('batch.outbox_event_id_seq'::regclass);

-- B) 建近 24 个月 + 未来 12 个月分区。生产用 03-add-future-partitions.sql cron 持续维护
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

-- C) 索引（PG 14+ 父表 CREATE INDEX 自动级联所有分区;对齐实库全部 5 个二级索引）
CREATE INDEX idx_outbox_p_aggregate ON batch.outbox_event_p (aggregate_type, aggregate_id);
CREATE INDEX idx_outbox_p_publish_status
    ON batch.outbox_event_p (publish_status, next_publish_at);
CREATE INDEX idx_outbox_p_tenant_status_scheduled
    ON batch.outbox_event_p (tenant_id, publish_status, next_publish_at);
CREATE INDEX idx_outbox_p_payload_json_gin
    ON batch.outbox_event_p USING gin (payload_json);
-- OutboxPollScheduler 高优先派发扫描(partial)
CREATE INDEX idx_outbox_p_priority_pending
    ON batch.outbox_event_p (publish_status, next_publish_at NULLS FIRST, priority DESC, id)
    WHERE publish_status IN ('NEW','FAILED');

-- D) 复制数据(显式全列)
INSERT INTO batch.outbox_event_p (
    id, tenant_id, aggregate_type, aggregate_id, event_type, event_key, payload_json,
    publish_status, publish_attempt, next_publish_at, trace_id, created_at, updated_at,
    priority
)
SELECT
    id, tenant_id, aggregate_type, aggregate_id, event_type, event_key, payload_json,
    publish_status, publish_attempt, next_publish_at, trace_id, created_at, updated_at,
    priority
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

\echo '=== 列数 vs legacy（应一致,防 schema 漂移复发） ==='
SELECT
    (SELECT count(*) FROM information_schema.columns WHERE table_schema='batch' AND table_name='outbox_event') AS new_cols,
    (SELECT count(*) FROM information_schema.columns WHERE table_schema='batch' AND table_name='outbox_event_legacy') AS legacy_cols;

\echo '=== 验证后请手动 DROP TABLE batch.outbox_event_legacy CASCADE; ==='
