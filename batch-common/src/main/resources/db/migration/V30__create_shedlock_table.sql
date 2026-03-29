-- 各模块通过 @SchedulerLock 共用的 ShedLock 表。
-- 放在 batch-common，依赖本模块的服务启动时由 Flyway 统一建表。

CREATE TABLE IF NOT EXISTS batch.shedlock (
    name        VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until  TIMESTAMPTZ  NOT NULL,
    locked_at   TIMESTAMPTZ  NOT NULL,
    locked_by   VARCHAR(255) NOT NULL
);
