-- V217：为 AI 审计保留策略增加按创建时间清理索引。
-- flyway:executeInTransaction=false

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_console_ai_audit_created_at
    ON batch.console_ai_audit_log (created_at);
