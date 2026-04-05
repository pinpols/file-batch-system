-- 清空平台库 Flyway 历史表，便于在「库中对象已存在但 history 丢失/错乱」时由应用重新 migrate 写入正确记录。
-- 迁移脚本大量使用 CREATE IF NOT EXISTS / 可重复 DDL，一般可与已有表共存。
--
-- 用法（与 application-local 默认一致时可照抄）：
--   psql "postgresql://batch_user:batch_pass_123@localhost:15432/batch_platform" -v ON_ERROR_STOP=1 -f scripts/db/reset_platform_flyway_history.sql
--
-- 之后：先停 orchestrator / trigger，执行本脚本，再启动其一（两者共用同一库与同一套 classpath:db/migration，勿并行 migrate）。

BEGIN;

CREATE SCHEMA IF NOT EXISTS batch;

-- Flyway 将历史表建在 default-schema（batch）下；尚无表时跳过（全新库直接启动应用即可）
DO $$
BEGIN
  IF to_regclass('batch.flyway_schema_history') IS NOT NULL THEN
    EXECUTE 'TRUNCATE TABLE batch.flyway_schema_history RESTART IDENTITY';
  END IF;
END;
$$;

COMMIT;
