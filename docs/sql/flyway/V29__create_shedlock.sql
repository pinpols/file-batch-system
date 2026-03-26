-- =========================================================
-- V29 - ShedLock distributed lock table
--
-- Prevents multiple orchestrator instances from executing
-- the same @Scheduled task concurrently.
-- One row per named lock; row is upserted on lock acquire
-- and cleared (lock_until reset to past) on release.
-- =========================================================

CREATE TABLE IF NOT EXISTS batch.shedlock (
    name       VARCHAR(64)                 NOT NULL,
    lock_until TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    locked_at  TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    locked_by  VARCHAR(255)                NOT NULL,
    CONSTRAINT pk_shedlock PRIMARY KEY (name)
);
