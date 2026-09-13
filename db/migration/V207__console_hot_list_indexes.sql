-- flyway:executeInTransaction=false
-- Console hot-list indexes for cursor pagination and selective fuzzy search.
-- Use CONCURRENTLY because these tables are written by the main control plane.

-- File list fuzzy file_name search: keep substring search semantics, but avoid sequential scans.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_file_record_file_name_trgm
    ON batch.file_record USING GIN (file_name gin_trgm_ops);

-- File list cursor pagination: tenant filter + id keyset.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_file_record_tenant_id_desc
    ON batch.file_record (tenant_id, id DESC);

-- Arrival group governance filters over metadata_json expressions.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_file_record_arrival_group_code_expr
    ON batch.file_record (tenant_id, (metadata_json ->> 'fileGroupCode'))
    WHERE metadata_json ? 'fileGroupCode';

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_file_record_arrival_state_expr
    ON batch.file_record (tenant_id, (coalesce(metadata_json ->> 'arrivalState', 'WAITING_ARRIVAL')))
    WHERE metadata_json ? 'fileGroupCode';

-- Console outbox and AI audit cursor pagination.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_event_outbox_retry_tenant_id_desc
    ON batch.event_outbox_retry (tenant_id, id DESC);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_event_delivery_log_tenant_id_desc
    ON batch.event_delivery_log (tenant_id, id DESC);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_console_ai_audit_tenant_id_desc
    ON batch.console_ai_audit_log (tenant_id, id DESC);

-- Trace lookups are exact-match diagnostic queries; keep them index-backed.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_workflow_run_trace_id
    ON batch.workflow_run (trace_id);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_event_delivery_log_trace_id
    ON batch.event_delivery_log (trace_id);
