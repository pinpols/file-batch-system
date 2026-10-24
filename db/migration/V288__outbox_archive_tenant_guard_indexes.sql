-- flyway:executeInTransaction=false

-- The global archive candidate scan verifies that child ownership matches the parent Outbox row.
-- Keep both anti-joins indexed so the integrity guard does not scan all delivery/retry history.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_event_delivery_log_outbox_tenant
    ON batch.event_delivery_log (outbox_event_id, tenant_id);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_event_outbox_retry_outbox_tenant
    ON batch.event_outbox_retry (outbox_event_id, tenant_id);
