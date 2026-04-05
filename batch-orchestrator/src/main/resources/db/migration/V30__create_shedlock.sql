-- =========================================================
-- V29 - ShedLock distributed lock table
-- Notes:
-- 1) Prevent multiple orchestrator instances from running the same scheduled task.
-- 2) Keep one row per named lock and let the library manage lock acquire/release.
-- =========================================================

CREATE TABLE IF NOT EXISTS batch.shedlock (
    name       VARCHAR(64)                 NOT NULL,
    lock_until TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    locked_at  TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    locked_by  VARCHAR(255)                NOT NULL,
    CONSTRAINT pk_shedlock PRIMARY KEY (name)
);
