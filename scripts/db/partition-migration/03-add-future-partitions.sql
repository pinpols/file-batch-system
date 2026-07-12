-- =========================================================
-- 03-add-future-partitions.sql
-- 持续维护：每月跑一次，在 outbox_event / job_instance 上创建未来 N 个月的分区，
-- 防止数据落到 DEFAULT 分区（DEFAULT 一旦有数据后再加常规分区会失败）。
--
-- 推荐 cron（每月 1 号 02:00）：
--   0 2 1 * *  PGPASSWORD=$PGPW psql -U batch_user -d batch_platform \
--               -v ON_ERROR_STOP=1 -v months_ahead=6 \
--               -f /opt/batch/scripts/db/partition-migration/03-add-future-partitions.sql
--
-- 自定义未来月份数：psql ... -v months_ahead=12 -f ...
--
-- 【容错设计】每个 (表, 月) 的分区创建独立成块并捕获异常：单个月失败不再级联中止
-- 后续月份与另一张表（旧版单 DO 块 + ON_ERROR_STOP 会因一处报错整段回滚，令分区维护
-- 静默瘫痪、数据持续堆入 DEFAULT）。失败会 RAISE WARNING 且末尾汇总 RAISE EXCEPTION，
-- 使脚本非零退出，cron/告警能感知——失败不静默。
--
-- 最常见失败：DEFAULT 分区已捕获目标月数据（漏跑一次 cron，或 biz_date 被 backfill/
-- 前置到未来窗口之外），此时该月 attach 报 "default partition would be violated by some row"。
-- 本脚本不自动 DETACH+迁数据（有并发写风险，不宜在无人值守 cron 里做）；处置见
-- docs/runbook 里的手工步骤：DETACH DEFAULT → CREATE 该月分区 → 从旧 DEFAULT 迁回该月行 → 重新 ATTACH DEFAULT。
-- =========================================================

\set ON_ERROR_STOP on

\if :{?months_ahead}
\else
  \set months_ahead 6
\endif

-- psql 变量无法在 $$ 块内插值,经 set_config 传入,DO 块里 current_setting 取回。
SELECT set_config('batch.months_ahead', :'months_ahead'::text, false);

DO $$
DECLARE
    months_ahead INT := current_setting('batch.months_ahead')::int;
    target_table TEXT;
    partition_key TEXT;
    m INT;
    start_month DATE;
    end_month DATE;
    pname TEXT;
    parent TEXT;
    failed INT := 0;
    failed_list TEXT := '';
BEGIN
    FOR target_table, partition_key IN VALUES
        ('outbox_event', 'created_at'),
        ('job_instance', 'biz_date')
    LOOP
        FOR m IN 0..months_ahead LOOP
            start_month := date_trunc('month', CURRENT_DATE) + (m || ' months')::interval;
            end_month := start_month + interval '1 month';
            pname := target_table || '_p_' || to_char(start_month, 'YYYY_MM');
            parent := 'batch.' || target_table;
            -- 每个分区独立子块：失败只跳过本月,不影响其余月份与另一张表。
            BEGIN
                EXECUTE format(
                    'CREATE TABLE IF NOT EXISTS batch.%I PARTITION OF %s '
                    'FOR VALUES FROM (%L) TO (%L)',
                    pname, parent, start_month, end_month);
            EXCEPTION WHEN OTHERS THEN
                failed := failed + 1;
                failed_list := failed_list || format(E'\n  - %s [%s~%s): %s', pname, start_month, end_month, SQLERRM);
                RAISE WARNING 'partition create failed for batch.% [%~%): % (跳过本月,继续其余)',
                    pname, start_month, end_month, SQLERRM;
            END;
        END LOOP;
    END LOOP;

    IF failed > 0 THEN
        -- 汇总后抛出,使脚本非零退出被 cron/告警捕获——失败不静默。
        -- 最常见根因:DEFAULT 分区已含目标月行,需手工 DETACH+迁数据(见文件头注释)。
        RAISE EXCEPTION 'add-future-partitions: % 个分区创建失败,分区维护未完全成功:%', failed, failed_list;
    END IF;
END$$;

\echo '=== 当前 outbox_event 分区数 ==='
SELECT count(*) AS partitions
FROM pg_inherits
WHERE inhparent = 'batch.outbox_event'::regclass;

\echo '=== 当前 job_instance 分区数 ==='
SELECT count(*) AS partitions
FROM pg_inherits
WHERE inhparent = 'batch.job_instance'::regclass;
