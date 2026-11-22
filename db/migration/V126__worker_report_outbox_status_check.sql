-- ============================================================================
-- V126: batch.worker_report_outbox.publish_status 补 CHECK 约束
-- ----------------------------------------------------------------------------
-- R7-A3-P2 收尾：V96 创建表时 publish_status VARCHAR(32) NOT NULL 但缺 CHECK；
-- Java 侧（WorkerReportOutboxRepository）使用字符串字面量 NEW / PUBLISHING /
-- GIVE_UP，未走 OutboxPublishStatus DictEnum。任何拼写错误（例如写成 published）
-- DB 不拦截，违反「领域字典」+「事件路由政策」枚举对齐原则。
--
-- 处置：与 V125 同模式 — 加 CHECK，5 个合法值；NOT VALID 避免历史数据校验失败。
-- 与 OutboxPublishStatus enum 同步：NEW / PUBLISHING / PUBLISHED / FAILED / GIVE_UP。
-- ============================================================================

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_worker_report_outbox_publish_status') THEN
        ALTER TABLE batch.worker_report_outbox
            ADD CONSTRAINT ck_worker_report_outbox_publish_status
            CHECK (publish_status IN ('NEW','PUBLISHING','PUBLISHED','FAILED','GIVE_UP'))
            NOT VALID;
    END IF;
END $$;
