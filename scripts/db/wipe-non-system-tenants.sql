-- 用途：预览或清理非系统租户；默认只预览，不删除无租户登记的孤儿数据。
-- 用法：psql -X -v ON_ERROR_STOP=1 -f scripts/db/wipe-non-system-tenants.sql
-- 破坏性操作：仅对经授权的目标平台库执行；删除会影响非系统租户及关联回执，不能由脚本撤销。
-- 删除前必须核对预览目标及跨租户影响；误删时按 scripts/db/README.md 从已验证备份恢复。
-- 删除前停止写方、验证备份恢复，并按 README 显式确认数据库、预览目标及备份。
-- 白名单：system、default、default-tenant、ta、tb、tc、tx。

\set ON_ERROR_STOP on

\if :{?action}
\else
  \set action preview
\endif
SELECT (:'action' IN ('preview', 'delete')) AS action_valid,
       (:'action' = 'delete') AS perform_delete \gset
\if :action_valid
\else
  DO $$ BEGIN RAISE EXCEPTION 'action 只能是 preview 或 delete'; END $$;
\endif
BEGIN;
SELECT (rolsuper OR rolbypassrls) AS can_inspect_all_tenants
  FROM pg_roles WHERE rolname = current_user \gset
\if :can_inspect_all_tenants
\else
  DO $$ BEGIN RAISE EXCEPTION '需使用可跨租户检查 RLS 的数据库运维角色'; END $$;
\endif
\if :perform_delete
  LOCK TABLE batch.tenant IN SHARE MODE;
\endif

\set keep '(''system'',''default'',''default-tenant'',''ta'',''tb'',''tc'',''tx'')'
CREATE TEMP TABLE wipe_target_tenants (tenant_id text PRIMARY KEY);
INSERT INTO wipe_target_tenants SELECT tenant_id FROM batch.tenant WHERE tenant_id NOT IN :keep;

SELECT COALESCE(string_agg(tenant_id, ',' ORDER BY tenant_id), '<none>') AS target_scope
  FROM wipe_target_tenants \gset
\echo '目标数据库和待清理租户（请核对）：'
SELECT current_database() AS target_database, :'target_scope' AS target_tenants;
\echo '跨租户影响：待删审计操作级联删除保留租户的回执：'
SELECT receipt.audit_tenant_id, receipt.tenant_id AS affected_tenant_id, count(*) AS receipt_count
  FROM batch.config_batch_target_receipt receipt
 WHERE receipt.audit_tenant_id IN (SELECT tenant_id FROM wipe_target_tenants)
   AND receipt.tenant_id NOT IN (SELECT tenant_id FROM wipe_target_tenants)
 GROUP BY receipt.audit_tenant_id, receipt.tenant_id ORDER BY 1, 2;
\echo '跨租户影响：保留审计操作中待删目标的回执将缺失并显示 UNKNOWN：'
SELECT receipt.audit_tenant_id, receipt.tenant_id AS affected_tenant_id, count(*) AS receipt_count
  FROM batch.config_batch_target_receipt receipt
 WHERE receipt.audit_tenant_id NOT IN (SELECT tenant_id FROM wipe_target_tenants)
   AND receipt.tenant_id IN (SELECT tenant_id FROM wipe_target_tenants)
 GROUP BY receipt.audit_tenant_id, receipt.tenant_id ORDER BY 1, 2;
SELECT count(*) AS cross_tenant_parent_references
  FROM batch.job_instance child JOIN batch.job_instance parent ON child.parent_instance_id = parent.id
 WHERE parent.tenant_id IN (SELECT tenant_id FROM wipe_target_tenants)
   AND child.tenant_id NOT IN (SELECT tenant_id FROM wipe_target_tenants);
\if :perform_delete
  \if :{?confirm_database}
  \else
    DO $$ BEGIN RAISE EXCEPTION '删除需确认 confirm_database'; END $$;
  \endif
  \if :{?confirm_targets}
  \else
    DO $$ BEGIN RAISE EXCEPTION '删除需确认 confirm_targets'; END $$;
  \endif
  \if :{?confirm_backup}
  \else
    DO $$ BEGIN RAISE EXCEPTION '删除需确认已验证备份 confirm_backup=verified'; END $$;
  \endif
  SELECT current_database() = :'confirm_database' AND :'target_scope' = :'confirm_targets'
     AND :'confirm_backup' = 'verified' AS confirmed,
     NOT EXISTS (
       SELECT 1 FROM batch.job_instance child JOIN batch.job_instance parent ON child.parent_instance_id = parent.id
        WHERE parent.tenant_id IN (SELECT tenant_id FROM wipe_target_tenants)
          AND child.tenant_id NOT IN (SELECT tenant_id FROM wipe_target_tenants)
     ) AS parent_references_safe \gset
  \if :confirmed
  \else
    DO $$ BEGIN RAISE EXCEPTION '数据库、目标租户或备份确认不匹配'; END $$;
  \endif
  \if :parent_references_safe
  \else
    DO $$ BEGIN RAISE EXCEPTION '保留租户仍引用待删父实例，请先按恢复流程处理'; END $$;
  \endif
-- ── 运行态:事件 / 出站 / 重试 / 补偿 / 审批 ────────────────────────────
DELETE FROM batch.event_delivery_log        WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
DELETE FROM batch.event_outbox_retry        WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
DELETE FROM batch.outbox_event              WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
DELETE FROM batch.retry_schedule            WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
DELETE FROM batch.compensation_command      WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
DELETE FROM batch.approval_command          WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
DELETE FROM batch.idempotency_record        WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
DELETE FROM batch.dead_letter_task          WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
-- 删除目标租户回执；保留审计租户的原目标列表，缺失回执不得当作失败或完成。
DELETE FROM batch.config_batch_target_receipt WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
-- 清理审计租户的操作时，由外键级联删除该操作的其余目标回执。
DELETE FROM batch.config_batch_operation WHERE audit_tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);

-- ── Workflow 运行实例(workflow_node_run 无 tenant_id,走 workflow_run 子查询) ──
DELETE FROM batch.workflow_node_run
 WHERE workflow_run_id IN (SELECT id FROM batch.workflow_run WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants));
DELETE FROM batch.workflow_run              WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);

-- ── Pipeline 运行实例(必须先于 job_instance 删,FK:pipeline_instance.related_job_instance_id → job_instance) ──
DELETE FROM batch.pipeline_progress         WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
DELETE FROM batch.pipeline_step_run
 WHERE pipeline_instance_id IN (SELECT id FROM batch.pipeline_instance WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants));
DELETE FROM batch.pipeline_instance         WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);

-- ── 作业实例链路:task → step → partition → instance ──────────────────
DELETE FROM batch.job_task                  WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
DELETE FROM batch.job_step_instance         WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
DELETE FROM batch.job_partition             WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
-- job_instance.parent_instance_id 自引,先 NULL 再删
UPDATE batch.job_instance SET parent_instance_id = NULL
 WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants)
   AND parent_instance_id IN (SELECT id FROM batch.job_instance WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants));
DELETE FROM batch.job_instance              WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);

-- ── 文件相关(在 pipeline_instance / job_instance 都删之后,FK 才安全) ──
DELETE FROM batch.file_error_record         WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
DELETE FROM batch.file_dispatch_record      WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
DELETE FROM batch.file_audit_log            WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
DELETE FROM batch.file_record               WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
DELETE FROM batch.file_channel_health       WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);

-- ── 触发 / 批次日 / 配额状态 / 快照 ──────────────────────────────────
DELETE FROM batch.job_dependency_waiting_launch WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
DELETE FROM batch.workflow_launch_wait          WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
DELETE FROM batch.batch_day_waiting_launch     WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
DELETE FROM batch.trigger_request           WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
DELETE FROM batch.batch_day_instance        WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
DELETE FROM batch.quota_runtime_state       WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
DELETE FROM batch.tenant_scheduler_snapshot WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);

-- ── 日志 / 通知 / 告警 ────────────────────────────────────────────────
DELETE FROM batch.alert_event               WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
DELETE FROM batch.notification_delivery_log WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
DELETE FROM batch.job_execution_log         WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
DELETE FROM batch.console_ai_audit_log      WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
DELETE FROM batch.webhook_delivery_log      WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
-- 回执删除在两个接收方表清理之后执行（V328 的 NO ACTION 引用）。
DELETE FROM batch.console_push_job_delivery WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
DELETE FROM batch.workflow_terminal_notification_receipt WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
DELETE FROM batch.config_change_log         WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
DELETE FROM batch.config_sync_log           WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
DELETE FROM batch.config_release            WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
DELETE FROM batch.config_approval           WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);

-- ── Workflow 配置(edge / node 无 tenant_id,走 workflow_definition 子查询) ──
DELETE FROM batch.workflow_edge
 WHERE workflow_definition_id IN (SELECT id FROM batch.workflow_definition WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants));
DELETE FROM batch.workflow_node
 WHERE workflow_definition_id IN (SELECT id FROM batch.workflow_definition WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants));
DELETE FROM batch.workflow_definition       WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);

-- ── Pipeline 配置 ──────────────────────────────────────────────────────

-- ── Job 定义 ──────────────────────────────────────────────────────────
DELETE FROM batch.job_definition            WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);

-- ── 资源 / 日历 / 窗口 / 配额策略 ───────────────────────────────────
DELETE FROM batch.resource_queue            WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
DELETE FROM batch.resource_tag              WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
DELETE FROM batch.batch_window              WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
DELETE FROM batch.batch_day_catchup_control_event WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
DELETE FROM batch.batch_day_catchup_control  WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
DELETE FROM batch.calendar_holiday
 WHERE calendar_id IN (SELECT id FROM batch.business_calendar WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants));
DELETE FROM batch.business_calendar         WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
DELETE FROM batch.tenant_quota_policy       WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);

-- ── 告警路由 / 通知 / 订阅 ────────────────────────────────────────────
DELETE FROM batch.alert_routing_config      WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
DELETE FROM batch.notification_channel      WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
DELETE FROM batch.subscription_rule         WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
DELETE FROM batch.webhook_subscription      WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);

-- ── 文件通道 / 模板 / 归档策略 ────────────────────────────────────────
DELETE FROM batch.file_channel_config       WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
DELETE FROM batch.file_template_config      WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
DELETE FROM batch.archive_policy            WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);

-- ── 安全 / Worker / API / 用户 / 参数 / 租户本体 ──────────────────────
DELETE FROM batch.secret_version            WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
DELETE FROM batch.worker_registry           WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
DELETE FROM batch.api_key                   WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
DELETE FROM batch.console_user_account      WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
DELETE FROM batch.system_parameter          WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
DELETE FROM batch.tenant                    WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);

COMMIT;

-- ── 验证残留 ────────────────────────────────────────────────────────────
\echo ''
\echo '== 清理后剩余租户(只展示仍存在的白名单租户)=='
SELECT tenant_id, tenant_name, status FROM batch.tenant ORDER BY tenant_id;

\echo ''
\echo '== 各表残留行数(应为 0)=='
SELECT 'job_definition'   AS t, count(*) FROM batch.job_definition      WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants)
UNION ALL SELECT 'job_instance',     count(*) FROM batch.job_instance        WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants)
UNION ALL SELECT 'workflow_def',     count(*) FROM batch.workflow_definition WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants)
UNION ALL SELECT 'file_template',    count(*) FROM batch.file_template_config WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants)
UNION ALL SELECT 'file_channel',     count(*) FROM batch.file_channel_config  WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants)
UNION ALL SELECT 'console_user',     count(*) FROM batch.console_user_account WHERE tenant_id IN (SELECT tenant_id FROM wipe_target_tenants);
-- DANGER: 批量删除所有非系统租户及关联数据。执行前必须核对数据库、备份和保留租户名单。

\else
  ROLLBACK;
  \echo '预览完成，未删除任何数据。'
\endif
