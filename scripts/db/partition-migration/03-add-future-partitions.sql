-- =========================================================
-- 03-add-future-partitions.sql
-- 演练入口：在 outbox_event / job_instance 上创建当前月到未来 N 个月的分区。
-- 生产维护责任与审批按本目录 README / PostgreSQL 分区运维手册执行。
-- DEFAULT 中只有落在目标新边界内的行会与该分区创建冲突。
--
-- 自定义未来月份数：psql ... -v months_ahead=12 -f ...
--
-- 【整批事务】内层异常块允许继续尝试其它 (表, 月) 并汇总失败；它不单独提交 DDL。
-- 任一创建失败时，末尾 RAISE EXCEPTION 回滚本次 DO 中全部新建分区并让脚本非零退出。
-- 本次执行前已提交的分区保持不变；全部创建成功才整体提交，重复执行保持幂等。
--
-- 最常见失败：DEFAULT 分区已捕获目标月数据（漏跑一次 cron，或 biz_date 被 backfill/
-- 前置到未来窗口之外），此时该月 attach 报 "default partition would be violated by some row"。
-- 本脚本不自动 DETACH 或迁移数据；冲突时按 docs/runbook/pg-table-partitioning.md
-- 保存边界与行数证据，提交 DBA 制定受控变更方案，不以手工改表绕过失败。
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
            -- 子块失败后继续收集其它月份的问题；最终任一失败会回滚整个 DO。
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
        -- DEFAULT 中目标月已有行时，需按受控数据库变更流程处置，不能自动迁移。
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
