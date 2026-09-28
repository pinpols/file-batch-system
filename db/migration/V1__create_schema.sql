-- =========================================================
-- V1 - 创建平台 schema
-- 说明：
-- 1) Quartz qrtz_* 表应使用官方 SQL 脚本初始化。
-- 2) 本迁移仅创建 schema 边界。
-- =========================================================

CREATE SCHEMA IF NOT EXISTS batch;
CREATE SCHEMA IF NOT EXISTS quartz;

COMMENT ON SCHEMA batch IS 'Batch scheduling platform business schema.';
COMMENT ON SCHEMA quartz IS 'Quartz JDBC JobStore schema.';
