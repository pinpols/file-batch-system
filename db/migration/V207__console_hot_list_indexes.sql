-- flyway:executeInTransaction=false
-- 为控制台高频列表的游标分页和指定字段模糊搜索创建索引。
-- 这些表由控制面持续写入，因此使用 CONCURRENTLY 创建索引。

-- 文件列表 file_name 模糊搜索：保留子串匹配语义，同时避免顺序扫描。
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_file_record_file_name_trgm
    ON batch.file_record USING GIN (file_name gin_trgm_ops);

-- 文件列表游标分页：按租户过滤并使用 id 键集分页。
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_file_record_tenant_id_desc
    ON batch.file_record (tenant_id, id DESC);

-- 到达组治理查询会过滤 metadata_json 表达式。
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_file_record_arrival_group_code_expr
    ON batch.file_record (tenant_id, (metadata_json ->> 'fileGroupCode'))
    WHERE metadata_json ? 'fileGroupCode';

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_file_record_arrival_state_expr
    ON batch.file_record (tenant_id, (coalesce(metadata_json ->> 'arrivalState', 'WAITING_ARRIVAL')))
    WHERE metadata_json ? 'fileGroupCode';

-- 控制台 Outbox 与 AI 审计列表的游标分页。
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_event_outbox_retry_tenant_id_desc
    ON batch.event_outbox_retry (tenant_id, id DESC);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_event_delivery_log_tenant_id_desc
    ON batch.event_delivery_log (tenant_id, id DESC);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_console_ai_audit_tenant_id_desc
    ON batch.console_ai_audit_log (tenant_id, id DESC);

-- Trace 查询用于精确匹配诊断，应确保通过索引访问。
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_workflow_run_trace_id
    ON batch.workflow_run (trace_id);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_event_delivery_log_trace_id
    ON batch.event_delivery_log (trace_id);
