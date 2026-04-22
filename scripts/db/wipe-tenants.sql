-- =====================================================================
-- wipe-tenants.sql
-- 清空指定租户在 batch schema 下的全部运行态 + 配置态数据，用于前端跑一轮
-- 干净的 "tenant-init + Excel 导入" 验证。
--
-- 用法：
--   1. 先停 workers / trigger / orchestrator（或 drain 模式），避免删除过程中
--      有新写入进来踩 FK 空洞。
--   2. 改下面 :tenants 的值（默认 'ta','tb','tc'），可按需裁剪。
--   3. docker exec -i batch-postgres psql -U batch_user -d batch_platform \
--        -v ON_ERROR_STOP=1 < scripts/db/wipe-tenants.sql
--   4. 清完后：从前端登录对应账号，走 POST /api/console/config/tenant-init
--      重新初始化，再用 *-tenant-config-package-test.xlsx 导入验证各种场景。
--
-- 顺序：runtime → config → tenant (FK 依赖由深到浅)。
-- 所有 DELETE 都是幂等的——重复跑不会报错。
-- =====================================================================

\set ON_ERROR_STOP on
\set tenants '(''ta'',''tb'',''tc'')'

BEGIN;

-- ── 运行态：事件 / 出站 / 重试 / 补偿 / 审批 ────────────────────────────
DELETE FROM batch.event_delivery_log        WHERE tenant_id IN :tenants;
DELETE FROM batch.event_outbox_retry        WHERE tenant_id IN :tenants;
DELETE FROM batch.outbox_event              WHERE tenant_id IN :tenants;
DELETE FROM batch.retry_schedule            WHERE tenant_id IN :tenants;
DELETE FROM batch.compensation_checkpoint   WHERE tenant_id IN :tenants;
DELETE FROM batch.compensation_command      WHERE tenant_id IN :tenants;
DELETE FROM batch.approval_command          WHERE tenant_id IN :tenants;
DELETE FROM batch.idempotency_record        WHERE tenant_id IN :tenants;
DELETE FROM batch.dead_letter_task          WHERE tenant_id IN :tenants;

-- ── 文件相关 ────────────────────────────────────────────────────────────
DELETE FROM batch.file_error_record         WHERE tenant_id IN :tenants;
DELETE FROM batch.file_dispatch_record      WHERE tenant_id IN :tenants;
DELETE FROM batch.file_audit_log            WHERE tenant_id IN :tenants;
DELETE FROM batch.file_record               WHERE tenant_id IN :tenants;
DELETE FROM batch.file_channel_health       WHERE tenant_id IN :tenants;

-- ── 作业实例链路：task → step → partition → instance ──────────────────
DELETE FROM batch.job_task                  WHERE tenant_id IN :tenants;
DELETE FROM batch.job_step_instance         WHERE tenant_id IN :tenants;
DELETE FROM batch.job_partition             WHERE tenant_id IN :tenants;
DELETE FROM batch.job_instance              WHERE tenant_id IN :tenants;

-- ── Workflow 运行实例（workflow_node_run 无 tenant_id，走 workflow_run 子查询） ──
DELETE FROM batch.workflow_node_run
 WHERE workflow_run_id IN (SELECT id FROM batch.workflow_run WHERE tenant_id IN :tenants);
DELETE FROM batch.workflow_run              WHERE tenant_id IN :tenants;

-- ── Pipeline 运行实例（pipeline_step_run 无 tenant_id） ──────────────
DELETE FROM batch.pipeline_step_run
 WHERE pipeline_instance_id IN (SELECT id FROM batch.pipeline_instance WHERE tenant_id IN :tenants);
DELETE FROM batch.pipeline_instance         WHERE tenant_id IN :tenants;

-- ── 触发 / 批次日 / 配额状态 / 快照 ──────────────────────────────────
DELETE FROM batch.trigger_request           WHERE tenant_id IN :tenants;
DELETE FROM batch.batch_day_instance        WHERE tenant_id IN :tenants;
DELETE FROM batch.quota_runtime_state       WHERE tenant_id IN :tenants;
DELETE FROM batch.tenant_scheduler_snapshot WHERE tenant_id IN :tenants;

-- ── 日志 / 通知 / 告警 ────────────────────────────────────────────────
DELETE FROM batch.alert_event               WHERE tenant_id IN :tenants;
DELETE FROM batch.notification_delivery_log WHERE tenant_id IN :tenants;
DELETE FROM batch.job_execution_log         WHERE tenant_id IN :tenants;
DELETE FROM batch.console_ai_audit_log      WHERE tenant_id IN :tenants;
DELETE FROM batch.webhook_delivery_log      WHERE tenant_id IN :tenants;
DELETE FROM batch.config_change_log         WHERE tenant_id IN :tenants;
DELETE FROM batch.config_sync_log           WHERE tenant_id IN :tenants;
DELETE FROM batch.config_release            WHERE tenant_id IN :tenants;
DELETE FROM batch.config_approval           WHERE tenant_id IN :tenants;

-- ── Workflow 配置（edge / node 无 tenant_id，走 workflow_definition 子查询） ──
DELETE FROM batch.workflow_edge
 WHERE workflow_definition_id IN (SELECT id FROM batch.workflow_definition WHERE tenant_id IN :tenants);
DELETE FROM batch.workflow_node
 WHERE workflow_definition_id IN (SELECT id FROM batch.workflow_definition WHERE tenant_id IN :tenants);
DELETE FROM batch.workflow_definition       WHERE tenant_id IN :tenants;

-- ── Pipeline 配置 ──────────────────────────────────────────────────────
DELETE FROM batch.pipeline_step_definition
 WHERE pipeline_definition_id IN (SELECT id FROM batch.pipeline_definition WHERE tenant_id IN :tenants);
DELETE FROM batch.pipeline_definition       WHERE tenant_id IN :tenants;

-- ── Job 定义 ──────────────────────────────────────────────────────────
DELETE FROM batch.job_definition            WHERE tenant_id IN :tenants;

-- ── 资源 / 日历 / 窗口 / 配额策略 ───────────────────────────────────
DELETE FROM batch.resource_queue            WHERE tenant_id IN :tenants;
DELETE FROM batch.resource_tag              WHERE tenant_id IN :tenants;
DELETE FROM batch.batch_window              WHERE tenant_id IN :tenants;
DELETE FROM batch.calendar_holiday
 WHERE calendar_id IN (SELECT id FROM batch.business_calendar WHERE tenant_id IN :tenants);
DELETE FROM batch.business_calendar         WHERE tenant_id IN :tenants;
DELETE FROM batch.tenant_quota_policy       WHERE tenant_id IN :tenants;

-- ── 告警路由 / 通知 / 订阅 ────────────────────────────────────────────
DELETE FROM batch.alert_routing_config      WHERE tenant_id IN :tenants;
DELETE FROM batch.notification_channel      WHERE tenant_id IN :tenants;
DELETE FROM batch.subscription_rule         WHERE tenant_id IN :tenants;
DELETE FROM batch.webhook_subscription      WHERE tenant_id IN :tenants;

-- ── 文件通道 / 模板 / 归档策略 ────────────────────────────────────────
DELETE FROM batch.file_channel_config       WHERE tenant_id IN :tenants;
DELETE FROM batch.file_template_config      WHERE tenant_id IN :tenants;
DELETE FROM batch.archive_policy            WHERE tenant_id IN :tenants;

-- ── 安全 / Worker / API / 用户 / 参数 / 租户本体 ──────────────────────
DELETE FROM batch.secret_version            WHERE tenant_id IN :tenants;
DELETE FROM batch.worker_registry           WHERE tenant_id IN :tenants;
DELETE FROM batch.api_key                   WHERE tenant_id IN :tenants;
DELETE FROM batch.console_user_account      WHERE tenant_id IN :tenants;
DELETE FROM batch.system_parameter          WHERE tenant_id IN :tenants;
DELETE FROM batch.tenant                    WHERE tenant_id IN :tenants;

-- ── Quartz（触发器按 job_code 注册，跨租户共用 JOB_GROUP；若需彻底清 Quartz 条目
--     下面可选）。TriggerReconciler 每 30s 会基于 job_definition 权威态自动对账，
--     所以通常不需要手动删。若想立即清：
-- DELETE FROM quartz.qrtz_simple_triggers WHERE trigger_name LIKE 'T%\_%' ESCAPE '\';
-- DELETE FROM quartz.qrtz_cron_triggers   WHERE trigger_name LIKE 'T%\_%' ESCAPE '\';
-- DELETE FROM quartz.qrtz_triggers        WHERE trigger_name LIKE 'T%\_%' ESCAPE '\';
-- DELETE FROM quartz.qrtz_job_details     WHERE job_name    LIKE 'T%\_%' ESCAPE '\';

COMMIT;

-- ── 验证剩余 ────────────────────────────────────────────────────────────
\echo '== 清理后 ta/tb/tc 残留行数（应全部为 0）=='
SELECT 'job_definition'      AS t, count(*) FROM batch.job_definition      WHERE tenant_id IN ('ta','tb','tc')
UNION ALL SELECT 'job_instance',   count(*) FROM batch.job_instance        WHERE tenant_id IN ('ta','tb','tc')
UNION ALL SELECT 'trigger_request',count(*) FROM batch.trigger_request     WHERE tenant_id IN ('ta','tb','tc')
UNION ALL SELECT 'outbox_event',   count(*) FROM batch.outbox_event        WHERE tenant_id IN ('ta','tb','tc')
UNION ALL SELECT 'file_record',    count(*) FROM batch.file_record         WHERE tenant_id IN ('ta','tb','tc')
UNION ALL SELECT 'worker_registry',count(*) FROM batch.worker_registry    WHERE tenant_id IN ('ta','tb','tc')
UNION ALL SELECT 'tenant',         count(*) FROM batch.tenant              WHERE tenant_id IN ('ta','tb','tc');
