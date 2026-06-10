-- V170: outbox_event 转 created_at 月分区。
-- 配套:OutboxEventMapper insert 已改 NOT EXISTS(同分支后续提交),
-- 幂等语义决策见 docs/design/partition-idempotency-decision.md。
-- 列/约束/索引权威源:scripts/db/partition-migration/01-*.sql(2026-06-10 pg_dump 重生成)。
-- 注意:本迁移含全表复制,生产规模执行前评估窗口;当前为上线前阶段,数据量 <20 万行,秒级。

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

ALTER TABLE batch.event_outbox_retry
    DROP CONSTRAINT IF EXISTS event_outbox_retry_outbox_event_id_fkey;

ALTER TABLE batch.outbox_event RENAME TO outbox_event_legacy;
ALTER TABLE batch.outbox_event_p RENAME TO outbox_event;

-- 重建 FK（注意：PG 不允许跨分区表 FK；这里维持原 FK 形态需要 partition pruning 路径）
-- 由于分区键是 created_at，event_delivery_log 不带 created_at 列，做不到 native FK；
-- 改为应用层守护：cleanup-outbox-events.sql 已有级联删 event_delivery_log 步骤
-- 不重建 FK，留 doc 注释提醒。

-- outbox_event_id_seq 原来 OWNED BY outbox_event（现已改名 outbox_event_legacy）。
-- 先把 ownership 转移到新分区表（已改名 outbox_event）；
-- 否则 DROP outbox_event_legacy 会级联删 sequence，而 outbox_event 和
-- archive.outbox_event_archive 的 id DEFAULT 均依赖该 sequence。
ALTER SEQUENCE batch.outbox_event_id_seq OWNED BY batch.outbox_event.id;

-- archive.outbox_event_archive 用 LIKE INCLUDING DEFAULTS 复制了 batch.outbox_event 的
-- id DEFAULT（nextval(outbox_event_id_seq)），现在 sequence 已归 outbox_event 所有，
-- archive 表的 DEFAULT 引用的只是 sequence 对象本身（不是 ownership），可以安全保留。
-- 但分区父表的 DEFAULT 不会被继承到 archive，也无需清除。

DROP TABLE batch.outbox_event_legacy;
