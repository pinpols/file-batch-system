-- =========================================================
-- 一次性迁移:batch.process_staging 非分区旧表 → 天级 RANGE 分区
-- =========================================================
-- 🔴 危险 / 破坏性 DDL:本脚本 `DROP TABLE batch.process_staging CASCADE` 后按分区表重建,
--    PRIMARY KEY 改为 (batch_key, row_seq, staged_at)(承重墙级约束变更)。**执行前必须**:
--    ① 确认无 in-flight PROCESS 任务(维护窗口);② 核对依赖 process_staging 的 ON CONFLICT /
--    幂等写不受新 PK 影响;③ 执行后重跑 rls-phase-a.sql 恢复 RLS。
--    (禁令标记范式同 scripts/db/partition-migration/01-outbox-event-partitioned.sql 头注释。)
-- =========================================================
-- 背景:旧 process_staging 是普通堆表,FEEDBACK/孤儿清理用 DELETE 不缩文件,
-- 长期高水位膨胀(实测撑爆磁盘)。改为按 staged_at 天级 RANGE 分区后,
-- ProcessStagingOrphanCleaner 预建未来分区 + DROP 过期分区,DROP 即还空间给 OS。
--
-- 适用:已部署、process_staging 已是非分区表的环境(全新部署直接走
-- create_biz_tables.sql 即建成分区表,不需要本脚本)。
--
-- 安全性:process_staging 是 WAP 瞬态暂存表(行写于 COMPUTE、清于 FEEDBACK)。
-- in-flight 行丢失只会让对应 PROCESS run 失败重试,无业务数据损失。**仍建议在
-- 无 in-flight PROCESS 任务的维护窗口执行**,避免正在跑的 run 报错。
--
-- 幂等:已是分区表则跳过(NOTICE);非分区则 DROP + 重建为分区表。
-- 执行:psql -d batch_business -f migrate-process-staging-to-partitioned.sql
-- 执行后必须重跑 RLS:psql -d batch_business -f rls-phase-a.sql
-- =========================================================

\connect batch_business

DO $$
DECLARE
    v_is_partitioned BOOLEAN;
    v_exists         BOOLEAN;
BEGIN
    SELECT EXISTS (
        SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'batch' AND c.relname = 'process_staging'
    ) INTO v_exists;

    IF NOT v_exists THEN
        RAISE NOTICE 'batch.process_staging 不存在 → 跳过迁移(走 create_biz_tables.sql 建分区表)';
        RETURN;
    END IF;

    SELECT (c.relkind = 'p') INTO v_is_partitioned
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'batch' AND c.relname = 'process_staging';

    IF v_is_partitioned THEN
        RAISE NOTICE 'batch.process_staging 已是分区表 → 跳过(幂等)';
        RETURN;
    END IF;

    RAISE NOTICE 'batch.process_staging 是非分区表 → DROP 重建为天级 RANGE 分区表';
    -- staging 瞬态,直接 DROP(CASCADE 清掉索引/RLS policy);随后由下方 CREATE + rls-phase-a.sql 重建
    DROP TABLE batch.process_staging CASCADE;

    CREATE TABLE batch.process_staging (
        batch_key      TEXT        NOT NULL,
        row_seq        BIGSERIAL   NOT NULL,
        tenant_id      TEXT        NOT NULL,
        target_schema  TEXT        NOT NULL,
        target_table   TEXT        NOT NULL,
        payload        JSONB       NOT NULL,
        staged_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
        PRIMARY KEY (batch_key, row_seq, staged_at)
    ) PARTITION BY RANGE (staged_at);

    CREATE TABLE batch.process_staging_default
        PARTITION OF batch.process_staging DEFAULT;

    CREATE INDEX idx_process_staging_batch_key
        ON batch.process_staging (batch_key);
    CREATE INDEX idx_process_staging_tenant_batch
        ON batch.process_staging (tenant_id, batch_key);
    CREATE INDEX idx_process_staging_target_batch
        ON batch.process_staging (tenant_id, target_schema, target_table, batch_key);
    CREATE INDEX idx_process_staging_staged_at
        ON batch.process_staging (staged_at);

    ALTER TABLE batch.process_staging_default SET (
        autovacuum_vacuum_scale_factor        = 0.05,
        autovacuum_vacuum_threshold           = 1000,
        autovacuum_vacuum_insert_scale_factor = 0.05,
        autovacuum_analyze_scale_factor       = 0.05
    );

    RAISE NOTICE '迁移完成。请立即重跑 rls-phase-a.sql 恢复 RLS policy。';
END $$;
