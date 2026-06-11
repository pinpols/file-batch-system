-- V178: audit/notify 散表簇 15 张表复合 PK 化（Citus distributed 前置）
--
-- 目的：将 audit/notify 散表簇 15 张表的 PRIMARY KEY 从单列 (id) 改为复合 (tenant_id, id)，
--       这是 Citus distributed table 的前置条件（分片键必须在 PK 中）。
-- 参见：docs/analysis/citus-poc-gates-2026-06-11.md
--
-- === 量测结果 ===
--
--   · console_operation_audit
--       当前 PK：console_operation_audit_pkey (id)  目标：(tenant_id, id)
--       入站 FK：无  出站 FK：无
--       archive：无影子表（V132 archive_enabled=FALSE，不存在 archive.console_operation_audit_archive）
--
--   · console_ai_audit_log
--       当前 PK：console_ai_audit_log_pkey (id)  目标：(tenant_id, id)
--       入站 FK：无  出站 FK：无
--       已有 UK：uk_console_ai_audit_request (tenant_id, request_id)，保持不动
--       archive：无
--
--   · console_push_subscription
--       当前 PK：console_push_subscription_pkey (id)  目标：(tenant_id, id)
--       入站 FK：无  出站 FK：无
--       已有 UK：uq_console_push_subscription (tenant_id, username, endpoint)，保持不动
--       archive：无
--
--   · console_push_job_notification
--       当前 PK：console_push_job_notification_pkey (id)  目标：(tenant_id, id)
--       入站 FK：无  出站 FK：无
--       已有 UK：uq_console_push_job_notification (tenant_id, job_instance_id)，保持不动
--       archive：无
--
--   · console_push_approval_notification
--       当前 PK：console_push_approval_notification_pkey (id)  目标：(tenant_id, id)
--       入站 FK：无  出站 FK：无
--       已有 UK：uq_console_push_approval_notification (tenant_id, approval_no)，保持不动
--       archive：无
--
--   · notification_delivery_log
--       当前 PK：notification_delivery_log_pkey (id)  目标：(tenant_id, id)
--       入站 FK：无  出站 FK：无
--       archive：无
--
--   · webhook_delivery_log
--       当前 PK：webhook_delivery_log_pkey (id)  目标：(tenant_id, id)
--       出站 FK：webhook_delivery_log_subscription_id_fkey → batch.webhook_subscription(id)
--           webhook_subscription PK 仍单列，保持原 FK 不动（spec 规则：FK 所指表 PK 仍单列则不动）
--       archive：无
--
--   · forensic_export_log
--       当前 PK：forensic_export_log_pkey (id)  目标：(tenant_id, id)
--       入站 FK：无  出站 FK：无
--       已有 UNIQUE：(tenant_id, export_id)，保持不动
--       archive：无
--
--   · alert_event
--       当前 PK：alert_event_pkey (id)  目标：(tenant_id, id)
--       入站 FK：无  出站 FK：无
--       已有 UK：uk_alert_event_dedup (tenant_id, dedup_fingerprint)，保持不动
--       archive：无
--
--   · data_quality_check
--       当前 PK：data_quality_check_pkey (id)  目标：(tenant_id, id)
--       入站 FK：无  出站 FK：无（rule_id 是普通列引用，无显式 FK 约束）
--       archive：archive.data_quality_check_archive，PK = pk_data_quality_check_archive(id) (V147 加)
--
--   · idempotency_record
--       当前 PK：idempotency_record_pkey (id)  目标：(tenant_id, id)
--       入站 FK：无  出站 FK：无
--       on conflict (tenant_id, idempotency_key) do nothing — 含 tenant_id，不动
--       archive：无
--
--   · quota_runtime_state
--       当前 PK：quota_runtime_state_pkey (id)  目标：(tenant_id, id)
--       入站 FK：无  出站 FK：无
--       已有 UK：uk_quota_runtime_state (tenant_id, quota_scope, owner_code)，保持不动
--       archive：无
--
--   · result_version
--       当前 PK：result_version_pkey (id)  目标：(tenant_id, id)
--       入站 FK：无  出站 FK：无（approval_id 是普通列，无显式 FK 约束）
--       archive：archive.result_version_archive，PK = result_version_archive_pkey(id)（V108 INCLUDING ALL 自动带）
--
--   · config_change_log
--       当前 PK：config_change_log_pkey (id)  目标：(tenant_id, id)
--       入站 FK：无  出站 FK：无
--       archive：无
--
--   · config_sync_log
--       当前 PK：config_sync_log_pkey (id)  目标：(tenant_id, id)
--       入站 FK：无  出站 FK：无
--       archive：无
--
-- === FK 处置清单 ===
--
-- 保持不动（FK 所指表 PK 仍单列）：
--   · webhook_delivery_log_subscription_id_fkey → webhook_subscription (id)
--
-- === mapper 缺口 ===
--
-- ConsoleWebhookDeliveryLogMapper：findById/claimForRetry/markRetrySuccess/markRetryFailure/markGiveUp
--   均只用 WHERE id=#{id}；PK 变复合后需补 tenant_id（见 Step 4 Java 改动）
-- NotificationDeliveryLogMapper：updateStatus WHERE id=#{id} 缺 tenant_id（Step 4 补）
-- QuotaRuntimeStateMapper：updateWithCas WHERE id=#{id} 缺 tenant_id（Step 4 补）
-- 其余 mapper 所有 WHERE 均含 tenant_id，无需修改
--
-- 量测：入站 FK 合计 0 条；archive 有 PK 的 2 张：result_version_archive / data_quality_check_archive
--
-- 禁 psql 元命令；禁 BEGIN/COMMIT（Flyway 管事务）

-- ============================================================
-- 1. console_operation_audit
--    当前 PK：console_operation_audit_pkey (id)  目标：(tenant_id, id)
--    无 FK，无 archive 影子表
-- ============================================================

ALTER TABLE batch.console_operation_audit DROP CONSTRAINT console_operation_audit_pkey;
ALTER TABLE batch.console_operation_audit ADD CONSTRAINT console_operation_audit_pkey
    PRIMARY KEY (tenant_id, id);

-- ============================================================
-- 2. console_ai_audit_log
--    当前 PK：console_ai_audit_log_pkey (id)  目标：(tenant_id, id)
--    uk_console_ai_audit_request (tenant_id, request_id) 保持不动
-- ============================================================

ALTER TABLE batch.console_ai_audit_log DROP CONSTRAINT console_ai_audit_log_pkey;
ALTER TABLE batch.console_ai_audit_log ADD CONSTRAINT console_ai_audit_log_pkey
    PRIMARY KEY (tenant_id, id);

-- ============================================================
-- 3. console_push_subscription
--    当前 PK：console_push_subscription_pkey (id)  目标：(tenant_id, id)
-- ============================================================

ALTER TABLE batch.console_push_subscription DROP CONSTRAINT console_push_subscription_pkey;
ALTER TABLE batch.console_push_subscription ADD CONSTRAINT console_push_subscription_pkey
    PRIMARY KEY (tenant_id, id);

-- ============================================================
-- 4. console_push_job_notification
--    当前 PK：console_push_job_notification_pkey (id)  目标：(tenant_id, id)
-- ============================================================

ALTER TABLE batch.console_push_job_notification DROP CONSTRAINT console_push_job_notification_pkey;
ALTER TABLE batch.console_push_job_notification ADD CONSTRAINT console_push_job_notification_pkey
    PRIMARY KEY (tenant_id, id);

-- ============================================================
-- 5. console_push_approval_notification
--    当前 PK：console_push_approval_notification_pkey (id)  目标：(tenant_id, id)
-- ============================================================

ALTER TABLE batch.console_push_approval_notification DROP CONSTRAINT console_push_approval_notification_pkey;
ALTER TABLE batch.console_push_approval_notification ADD CONSTRAINT console_push_approval_notification_pkey
    PRIMARY KEY (tenant_id, id);

-- ============================================================
-- 6. notification_delivery_log
--    当前 PK：notification_delivery_log_pkey (id)  目标：(tenant_id, id)
-- ============================================================

ALTER TABLE batch.notification_delivery_log DROP CONSTRAINT notification_delivery_log_pkey;
ALTER TABLE batch.notification_delivery_log ADD CONSTRAINT notification_delivery_log_pkey
    PRIMARY KEY (tenant_id, id);

-- ============================================================
-- 7. webhook_delivery_log
--    当前 PK：webhook_delivery_log_pkey (id)  目标：(tenant_id, id)
--    出站 FK webhook_delivery_log_subscription_id_fkey → webhook_subscription(id) 保持不动
--    （webhook_subscription PK 仍单列）
-- ============================================================

ALTER TABLE batch.webhook_delivery_log DROP CONSTRAINT webhook_delivery_log_pkey;
ALTER TABLE batch.webhook_delivery_log ADD CONSTRAINT webhook_delivery_log_pkey
    PRIMARY KEY (tenant_id, id);

-- ============================================================
-- 8. forensic_export_log
--    当前 PK：forensic_export_log_pkey (id)  目标：(tenant_id, id)
-- ============================================================

ALTER TABLE batch.forensic_export_log DROP CONSTRAINT forensic_export_log_pkey;
ALTER TABLE batch.forensic_export_log ADD CONSTRAINT forensic_export_log_pkey
    PRIMARY KEY (tenant_id, id);

-- ============================================================
-- 9. alert_event
--    当前 PK：alert_event_pkey (id)  目标：(tenant_id, id)
-- ============================================================

ALTER TABLE batch.alert_event DROP CONSTRAINT alert_event_pkey;
ALTER TABLE batch.alert_event ADD CONSTRAINT alert_event_pkey
    PRIMARY KEY (tenant_id, id);

-- ============================================================
-- 10. data_quality_check
--     当前 PK：data_quality_check_pkey (id)  目标：(tenant_id, id)
-- ============================================================

ALTER TABLE batch.data_quality_check DROP CONSTRAINT data_quality_check_pkey;
ALTER TABLE batch.data_quality_check ADD CONSTRAINT data_quality_check_pkey
    PRIMARY KEY (tenant_id, id);

-- ============================================================
-- 11. idempotency_record
--     当前 PK：idempotency_record_pkey (id)  目标：(tenant_id, id)
--     on conflict (tenant_id, idempotency_key) do nothing — 含 tenant_id，不动
-- ============================================================

ALTER TABLE batch.idempotency_record DROP CONSTRAINT idempotency_record_pkey;
ALTER TABLE batch.idempotency_record ADD CONSTRAINT idempotency_record_pkey
    PRIMARY KEY (tenant_id, id);

-- ============================================================
-- 12. quota_runtime_state
--     当前 PK：quota_runtime_state_pkey (id)  目标：(tenant_id, id)
-- ============================================================

ALTER TABLE batch.quota_runtime_state DROP CONSTRAINT quota_runtime_state_pkey;
ALTER TABLE batch.quota_runtime_state ADD CONSTRAINT quota_runtime_state_pkey
    PRIMARY KEY (tenant_id, id);

-- ============================================================
-- 13. result_version
--     当前 PK：result_version_pkey (id)  目标：(tenant_id, id)
-- ============================================================

ALTER TABLE batch.result_version DROP CONSTRAINT result_version_pkey;
ALTER TABLE batch.result_version ADD CONSTRAINT result_version_pkey
    PRIMARY KEY (tenant_id, id);

-- ============================================================
-- 14. config_change_log
--     当前 PK：config_change_log_pkey (id)  目标：(tenant_id, id)
-- ============================================================

ALTER TABLE batch.config_change_log DROP CONSTRAINT config_change_log_pkey;
ALTER TABLE batch.config_change_log ADD CONSTRAINT config_change_log_pkey
    PRIMARY KEY (tenant_id, id);

-- ============================================================
-- 15. config_sync_log
--     当前 PK：config_sync_log_pkey (id)  目标：(tenant_id, id)
-- ============================================================

ALTER TABLE batch.config_sync_log DROP CONSTRAINT config_sync_log_pkey;
ALTER TABLE batch.config_sync_log ADD CONSTRAINT config_sync_log_pkey
    PRIMARY KEY (tenant_id, id);

-- ============================================================
-- 16. archive 镜像同步
--     CLAUDE.md：热表 batch.* 与 archive.*_archive 必须 1:1 字段镜像（PK 结构亦需对齐）
--
--     result_version_archive：PK = result_version_archive_pkey(id)（V108 INCLUDING ALL 自动命名）
--     data_quality_check_archive：PK = pk_data_quality_check_archive(id)（V147 手动加）
--
--     热表 PK 变复合，archive PK 同步改复合（archive 表是 INSERT-SELECT 路径，
--     PK 结构不一致不影响 ON CONFLICT 正确性，但需保持 1:1 镜像纪律）
-- ============================================================

-- 16a. archive.result_version_archive：改复合 PK
ALTER TABLE archive.result_version_archive DROP CONSTRAINT result_version_archive_pkey;
ALTER TABLE archive.result_version_archive ADD CONSTRAINT result_version_archive_pkey
    PRIMARY KEY (tenant_id, id);

-- 16b. archive.data_quality_check_archive：改复合 PK
ALTER TABLE archive.data_quality_check_archive DROP CONSTRAINT pk_data_quality_check_archive;
ALTER TABLE archive.data_quality_check_archive ADD CONSTRAINT pk_data_quality_check_archive
    PRIMARY KEY (tenant_id, id);
