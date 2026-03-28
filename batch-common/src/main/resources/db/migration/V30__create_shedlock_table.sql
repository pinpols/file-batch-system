-- ShedLock table shared by all batch modules using @SchedulerLock.
-- This migration lives in batch-common so every service that depends on it
-- can create the table through Flyway on startup.

CREATE TABLE IF NOT EXISTS batch.shedlock (
    name        VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until  TIMESTAMPTZ  NOT NULL,
    locked_at   TIMESTAMPTZ  NOT NULL,
    locked_by   VARCHAR(255) NOT NULL
);
