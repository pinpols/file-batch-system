-- ============================================================================
-- V153: console_push_approval_notification — 审批结果推送幂等去重表
-- ----------------------------------------------------------------------------
-- ConsolePushApprovalNotifier 周期扫 approval_command 终态(APPROVED/REJECTED/
-- EXECUTED),对有 requester_id 的逐条 push 通知到发起人。本表存"已推送过的
-- (tenant, approval_no)",poller 用 NOT EXISTS 过滤,UNIQUE 兜底防并发重复 INSERT。
--
-- 范围:仅 console-api 的推送链路 own 此表;非业务核心数据,无 archive 镜像。
-- ============================================================================

CREATE TABLE IF NOT EXISTS batch.console_push_approval_notification (
    id              BIGSERIAL    PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    approval_no     VARCHAR(128) NOT NULL,
    notified_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_console_push_approval_notification UNIQUE (tenant_id, approval_no)
);

CREATE INDEX IF NOT EXISTS idx_console_push_approval_notification_notified_at
    ON batch.console_push_approval_notification (notified_at);
