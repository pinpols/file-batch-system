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
            EXECUTE format(
                'CREATE TABLE IF NOT EXISTS batch.%I PARTITION OF %s '
                'FOR VALUES FROM (%L) TO (%L)',
                pname, parent, start_month, end_month);
        END LOOP;
    END LOOP;
END$$;

\echo '=== 当前 outbox_event 分区数 ==='
SELECT count(*) AS partitions
FROM pg_inherits
WHERE inhparent = 'batch.outbox_event'::regclass;

\echo '=== 当前 job_instance 分区数 ==='
SELECT count(*) AS partitions
FROM pg_inherits
WHERE inhparent = 'batch.job_instance'::regclass;
