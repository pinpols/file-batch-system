-- ShedLock：在 Flyway 跑完前若已有调度访问库，可避免 relation "batch.shedlock" does not exist。
-- 与 docs/sql/flyway/V29__create_shedlock.sql / batch-common 定义对齐（IF NOT EXISTS 可重复执行）。

CREATE TABLE IF NOT EXISTS batch.shedlock (
    name        VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until  TIMESTAMPTZ  NOT NULL,
    locked_at   TIMESTAMPTZ  NOT NULL,
    locked_by   VARCHAR(255) NOT NULL
);
