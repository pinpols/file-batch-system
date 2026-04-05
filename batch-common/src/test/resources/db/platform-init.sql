-- Testcontainers 平台库：仅与 Flyway V__create_schema.sql 对齐（建 schema 边界）。
-- 全量 DDL 由 Spring Flyway 在测试启动时从 batch-orchestrator 的 classpath:db/migration 执行。
-- 勿在此重复表结构，避免与迁移脚本分叉。

CREATE SCHEMA IF NOT EXISTS batch;
CREATE SCHEMA IF NOT EXISTS quartz;

COMMENT ON SCHEMA batch IS 'Batch scheduling platform business schema.';
COMMENT ON SCHEMA quartz IS 'Quartz JDBC JobStore schema.';
