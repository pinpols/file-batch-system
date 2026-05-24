-- ============================================================================
-- V152: console_push_job_notification — 任务终态推送幂等去重表
-- ----------------------------------------------------------------------------
-- ConsolePushJobNotifier 周期扫 job_instance 终态(SUCCESS/FAILED/CANCELLED/
-- TERMINATED/PARTIAL_FAILED),对每条仅推送一次。本表存"已推送过的 (tenant,
-- instance)",poller 用 NOT EXISTS 过滤,UNIQUE 兜底防并发重复 INSERT。
--
-- 范围:仅 console-api 的推送链路 own 此表;非业务核心数据,无 archive 镜像,
-- 也不参与 ArchiveSchemaDriftCheck(未登记 ARCHIVED_TABLES)。
-- ============================================================================

CREATE TABLE IF NOT EXISTS batch.console_push_job_notification (
    id              BIGSERIAL    PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    job_instance_id BIGINT       NOT NULL,
    notified_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_console_push_job_notification UNIQUE (tenant_id, job_instance_id)
);

-- poller 顺序按 notified_at 监控延迟,辅助索引非必需(UNIQUE 已含 (tenant_id, job_instance_id))。
CREATE INDEX IF NOT EXISTS idx_console_push_job_notification_notified_at
    ON batch.console_push_job_notification (notified_at);
