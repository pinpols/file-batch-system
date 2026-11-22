-- DANGER: 仅限确认目标的本地空库。已有平台对象时停止，先保留备份。
-- 清理空本地平台库的错误迁移历史。当前 V2 需要空库，已有平台表的数据库拒绝清理历史。
-- 重编号前的库应保留备份，通过独立空库部署新版本。
--
-- 用法：确认本地目标后执行配套 reset_platform_flyway_history.sh。

BEGIN;

CREATE SCHEMA IF NOT EXISTS batch;

-- Flyway 将历史表建在 default-schema（batch）下；尚无表时跳过（全新库直接启动应用即可）
DO $$
BEGIN
  IF EXISTS (
      SELECT 1
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
       WHERE n.nspname IN ('batch', 'archive', 'quartz')
         AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
         AND (n.nspname, c.relname) <> ('batch', 'flyway_schema_history')
  ) THEN
    RAISE EXCEPTION '现有平台库不能仅清空迁移历史，请先备份并在空库执行当前迁移';
  END IF;
  IF to_regclass('batch.flyway_schema_history') IS NOT NULL THEN
    EXECUTE 'TRUNCATE TABLE batch.flyway_schema_history RESTART IDENTITY';
  END IF;
END;
$$;

COMMIT;
-- DANGER: 清空 Flyway 历史版本记录会破坏迁移状态。仅限明确批准的本地恢复场景，执行前备份数据库。
