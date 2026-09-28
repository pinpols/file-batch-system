-- =========================================================
-- V29 - 创建 ShedLock 分布式锁表
-- 说明：
-- 1) 防止多个 orchestrator 实例同时执行同一个定时任务。
-- 2) 每个命名锁仅保留一条记录，锁的获取和释放由依赖库管理。
-- =========================================================

CREATE TABLE IF NOT EXISTS batch.shedlock (
    name       VARCHAR(64)                 NOT NULL,
    lock_until TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    locked_at  TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    locked_by  VARCHAR(255)                NOT NULL,
    CONSTRAINT pk_shedlock PRIMARY KEY (name)
);
