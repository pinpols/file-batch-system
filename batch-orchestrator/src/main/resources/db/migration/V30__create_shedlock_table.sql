-- ShedLock table for orchestrator scheduled tasks.
-- Used by @SchedulerLock to ensure cluster-wide single execution.

CREATE TABLE IF NOT EXISTS batch.shedlock (
    name        VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until  TIMESTAMPTZ  NOT NULL,
    locked_at   TIMESTAMPTZ  NOT NULL,
    locked_by   VARCHAR(255) NOT NULL
);

