CREATE SCHEMA IF NOT EXISTS batch;
CREATE SCHEMA IF NOT EXISTS quartz;

COMMENT ON SCHEMA batch IS 'Business schema for the batch scheduling platform.';
COMMENT ON SCHEMA quartz IS 'Quartz metadata schema.';
