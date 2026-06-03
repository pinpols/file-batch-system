-- =====================================================================
-- wipe-non-system-tenants.sql
-- 清空"白名单外"所有租户的全部数据,用于日常清理 e2e / 手测残留。
--
-- **白名单**(下方 :keep 必须保留):
--   - system          系统保留(V52 内置账号)
--   - default         系统模板(V55 内置)
--   - default-tenant  租户初始化复制源(platform_seed.sql)
--   - ta / tb / tc    长期 dev fixture(团队手测 + 部分 e2e 复用)
--   - tx              FE e2e RBAC matrix 长期 fixture(op-tx / user-tx)
--
-- 任何不在白名单的 tenant_id 视为残留,本脚本全部物理删 + 级联子表。
--
-- 用法:
--   1. 先停 workers / trigger / orchestrator(或 drain 模式)
--   2. docker exec -i batch-postgres psql -U batch_user -d batch_platform \
--        -v ON_ERROR_STOP=1 < scripts/db/wipe-non-system-tenants.sql
--   3. 终端会输出"清理前 → 清理后"对比表
--
-- 顺序:同 wipe-tenants.sql,runtime → config → tenant(FK 由深到浅)。
-- 所有 DELETE 都幂等。
-- =====================================================================

\set ON_ERROR_STOP on
\set keep '(''system'',''default'',''default-tenant'',''ta'',''tb'',''tc'',''tx'')'

\echo '== 清理前残留租户(白名单外)=='
SELECT tenant_id, tenant_name, status, created_at::date
  FROM batch.tenant
 WHERE tenant_id NOT IN :keep
 ORDER BY tenant_id;

BEGIN;

-- ── 运行态:事件 / 出站 / 重试 / 补偿 / 审批 ────────────────────────────
DELETE FROM batch.event_delivery_log        WHERE tenant_id NOT IN :keep;
DELETE FROM batch.event_outbox_retry        WHERE tenant_id NOT IN :keep;
DELETE FROM batch.outbox_event              WHERE tenant_id NOT IN :keep;
DELETE FROM batch.retry_schedule            WHERE tenant_id NOT IN :keep;
DELETE FROM batch.compensation_checkpoint   WHERE tenant_id NOT IN :keep;
DELETE FROM batch.compensation_command      WHERE tenant_id NOT IN :keep;
DELETE FROM batch.approval_command          WHERE tenant_id NOT IN :keep;
DELETE FROM batch.idempotency_record        WHERE tenant_id NOT IN :keep;
DELETE FROM batch.dead_letter_task          WHERE tenant_id NOT IN :keep;

-- ── Workflow 运行实例(workflow_node_run 无 tenant_id,走 workflow_run 子查询) ──
DELETE FROM batch.workflow_node_run
 WHERE workflow_run_id IN (SELECT id FROM batch.workflow_run WHERE tenant_id NOT IN :keep);
DELETE FROM batch.workflow_run              WHERE tenant_id NOT IN :keep;

-- ── Pipeline 运行实例(必须先于 job_instance 删,FK:pipeline_instance.related_job_instance_id → job_instance) ──
DELETE FROM batch.pipeline_step_run
 WHERE pipeline_instance_id IN (SELECT id FROM batch.pipeline_instance WHERE tenant_id NOT IN :keep);
DELETE FROM batch.pipeline_instance         WHERE tenant_id NOT IN :keep;

-- ── 作业实例链路:task → step → partition → instance ──────────────────
DELETE FROM batch.job_task                  WHERE tenant_id NOT IN :keep;
DELETE FROM batch.job_step_instance         WHERE tenant_id NOT IN :keep;
DELETE FROM batch.job_partition             WHERE tenant_id NOT IN :keep;
-- job_instance.parent_instance_id 自引,先 NULL 再删
UPDATE batch.job_instance SET parent_instance_id = NULL
 WHERE parent_instance_id IN (SELECT id FROM batch.job_instance WHERE tenant_id NOT IN :keep);
DELETE FROM batch.job_instance              WHERE tenant_id NOT IN :keep;

-- ── 文件相关(在 pipeline_instance / job_instance 都删之后,FK 才安全) ──
DELETE FROM batch.file_error_record         WHERE tenant_id NOT IN :keep;
DELETE FROM batch.file_dispatch_record      WHERE tenant_id NOT IN :keep;
DELETE FROM batch.file_audit_log            WHERE tenant_id NOT IN :keep;
DELETE FROM batch.file_record               WHERE tenant_id NOT IN :keep;
DELETE FROM batch.file_channel_health       WHERE tenant_id NOT IN :keep;

-- ── 触发 / 批次日 / 配额状态 / 快照 ──────────────────────────────────
DELETE FROM batch.trigger_request           WHERE tenant_id NOT IN :keep;
DELETE FROM batch.batch_day_instance        WHERE tenant_id NOT IN :keep;
DELETE FROM batch.quota_runtime_state       WHERE tenant_id NOT IN :keep;
DELETE FROM batch.tenant_scheduler_snapshot WHERE tenant_id NOT IN :keep;

-- ── 日志 / 通知 / 告警 ────────────────────────────────────────────────
DELETE FROM batch.alert_event               WHERE tenant_id NOT IN :keep;
DELETE FROM batch.notification_delivery_log WHERE tenant_id NOT IN :keep;
DELETE FROM batch.job_execution_log         WHERE tenant_id NOT IN :keep;
DELETE FROM batch.console_ai_audit_log      WHERE tenant_id NOT IN :keep;
DELETE FROM batch.webhook_delivery_log      WHERE tenant_id NOT IN :keep;
DELETE FROM batch.config_change_log         WHERE tenant_id NOT IN :keep;
DELETE FROM batch.config_sync_log           WHERE tenant_id NOT IN :keep;
DELETE FROM batch.config_release            WHERE tenant_id NOT IN :keep;
DELETE FROM batch.config_approval           WHERE tenant_id NOT IN :keep;

-- ── Workflow 配置(edge / node 无 tenant_id,走 workflow_definition 子查询) ──
DELETE FROM batch.workflow_edge
 WHERE workflow_definition_id IN (SELECT id FROM batch.workflow_definition WHERE tenant_id NOT IN :keep);
DELETE FROM batch.workflow_node
 WHERE workflow_definition_id IN (SELECT id FROM batch.workflow_definition WHERE tenant_id NOT IN :keep);
DELETE FROM batch.workflow_definition       WHERE tenant_id NOT IN :keep;

-- ── Pipeline 配置 ──────────────────────────────────────────────────────
DELETE FROM batch.pipeline_step_definition
 WHERE pipeline_definition_id IN (SELECT id FROM batch.pipeline_definition WHERE tenant_id NOT IN :keep);
DELETE FROM batch.pipeline_definition       WHERE tenant_id NOT IN :keep;

-- ── Job 定义 ──────────────────────────────────────────────────────────
DELETE FROM batch.job_definition            WHERE tenant_id NOT IN :keep;

-- ── 资源 / 日历 / 窗口 / 配额策略 ───────────────────────────────────
DELETE FROM batch.resource_queue            WHERE tenant_id NOT IN :keep;
DELETE FROM batch.resource_tag              WHERE tenant_id NOT IN :keep;
DELETE FROM batch.batch_window              WHERE tenant_id NOT IN :keep;
DELETE FROM batch.calendar_holiday
 WHERE calendar_id IN (SELECT id FROM batch.business_calendar WHERE tenant_id NOT IN :keep);
DELETE FROM batch.business_calendar         WHERE tenant_id NOT IN :keep;
DELETE FROM batch.tenant_quota_policy       WHERE tenant_id NOT IN :keep;

-- ── 告警路由 / 通知 / 订阅 ────────────────────────────────────────────
DELETE FROM batch.alert_routing_config      WHERE tenant_id NOT IN :keep;
DELETE FROM batch.notification_channel      WHERE tenant_id NOT IN :keep;
DELETE FROM batch.subscription_rule         WHERE tenant_id NOT IN :keep;
DELETE FROM batch.webhook_subscription      WHERE tenant_id NOT IN :keep;

-- ── 文件通道 / 模板 / 归档策略 ────────────────────────────────────────
DELETE FROM batch.file_channel_config       WHERE tenant_id NOT IN :keep;
DELETE FROM batch.file_template_config      WHERE tenant_id NOT IN :keep;
DELETE FROM batch.archive_policy            WHERE tenant_id NOT IN :keep;

-- ── 安全 / Worker / API / 用户 / 参数 / 租户本体 ──────────────────────
DELETE FROM batch.secret_version            WHERE tenant_id NOT IN :keep;
DELETE FROM batch.worker_registry           WHERE tenant_id NOT IN :keep;
DELETE FROM batch.api_key                   WHERE tenant_id NOT IN :keep;
DELETE FROM batch.console_user_account      WHERE tenant_id NOT IN :keep;
DELETE FROM batch.system_parameter          WHERE tenant_id NOT IN :keep;
DELETE FROM batch.tenant                    WHERE tenant_id NOT IN :keep;

COMMIT;

-- ── 验证残留 ────────────────────────────────────────────────────────────
\echo ''
\echo '== 清理后剩余租户(应只剩白名单 6 个)=='
SELECT tenant_id, tenant_name, status FROM batch.tenant ORDER BY tenant_id;

\echo ''
\echo '== 各表残留行数(应为 0)=='
SELECT 'job_definition'   AS t, count(*) FROM batch.job_definition      WHERE tenant_id NOT IN :keep
UNION ALL SELECT 'job_instance',     count(*) FROM batch.job_instance        WHERE tenant_id NOT IN :keep
UNION ALL SELECT 'workflow_def',     count(*) FROM batch.workflow_definition WHERE tenant_id NOT IN :keep
UNION ALL SELECT 'file_template',    count(*) FROM batch.file_template_config WHERE tenant_id NOT IN :keep
UNION ALL SELECT 'file_channel',     count(*) FROM batch.file_channel_config  WHERE tenant_id NOT IN :keep
UNION ALL SELECT 'console_user',     count(*) FROM batch.console_user_account WHERE tenant_id NOT IN :keep;
