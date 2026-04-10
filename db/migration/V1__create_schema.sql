-- =========================================================
-- V1 - Create platform schemas
-- Notes:
-- 1) Quartz qrtz_* tables should be initialized by the official Quartz SQL.
-- 2) This migration only creates schema boundaries.
-- =========================================================

CREATE SCHEMA IF NOT EXISTS batch;
CREATE SCHEMA IF NOT EXISTS quartz;

COMMENT ON SCHEMA batch IS 'Batch scheduling platform business schema.';
COMMENT ON SCHEMA quartz IS 'Quartz JDBC JobStore schema.';
