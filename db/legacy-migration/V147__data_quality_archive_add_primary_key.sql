-- ============================================================================
-- V147: data_quality_rule_archive / data_quality_check_archive 补 PRIMARY KEY
-- ============================================================================
--
-- 背景: V118 (ADR-021) 创建 archive.data_quality_{rule,check}_archive 时只用
--   `LIKE batch.<table> INCLUDING DEFAULTS INCLUDING CONSTRAINTS`,
-- 没有像 V71 DO $$ 块那样补 `ADD CONSTRAINT pk_*_archive PRIMARY KEY (id)`。
-- 结果: 两张归档表无主键, 后续归档 UPSERT 路径 `ON CONFLICT (id) DO NOTHING`
-- 会撞 "no unique or exclusion constraint matching the ON CONFLICT specification" 错。
-- 由 ArchiveSchemaDriftCheckIntegrationTest.everyArchiveTableHasPrimaryKey 捉到。
--
-- 补救: 跟 V71 DO $$ 块同模式 — IF NOT EXISTS 兜底, 重复执行无副作用。
-- ============================================================================

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        JOIN pg_namespace n ON n.oid = t.relnamespace
        WHERE n.nspname = 'archive'
          AND t.relname = 'data_quality_rule_archive'
          AND c.contype = 'p'
    ) THEN
        ALTER TABLE archive.data_quality_rule_archive
            ADD CONSTRAINT pk_data_quality_rule_archive PRIMARY KEY (id);
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        JOIN pg_namespace n ON n.oid = t.relnamespace
        WHERE n.nspname = 'archive'
          AND t.relname = 'data_quality_check_archive'
          AND c.contype = 'p'
    ) THEN
        ALTER TABLE archive.data_quality_check_archive
            ADD CONSTRAINT pk_data_quality_check_archive PRIMARY KEY (id);
    END IF;
END $$;
