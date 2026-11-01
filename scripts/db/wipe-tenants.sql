-- 用途：预览或清理明确列出的租户及关联数据；默认只预览。
-- 用法：psql -X -v ON_ERROR_STOP=1 -v tenant_ids=ta,tb,tc -f scripts/db/wipe-tenants.sql
-- 删除前停止写方、验证备份恢复，并按 README 显式确认数据库、预览目标及备份。
-- 保留租户引用待删除父实例时拒绝操作；重复目标按去重、排序后确认。

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

\if :{?tenant_ids}
\else
  DO $$ BEGIN RAISE EXCEPTION '必须通过 tenant_ids 指定逗号分隔的目标租户'; END $$;
\endif
CREATE TEMP TABLE wipe_target_tenants (tenant_id text PRIMARY KEY);
INSERT INTO wipe_target_tenants
SELECT DISTINCT trim(value) FROM unnest(string_to_array(:'tenant_ids', ',')) value;
SELECT count(*) > 0 AND bool_and(tenant_id <> '') AS targets_valid FROM wipe_target_tenants \gset
\if :targets_valid
\else
  DO $$ BEGIN RAISE EXCEPTION 'tenant_ids 不得为空或含空项'; END $$;
\endif
\set tenants '(SELECT tenant_id FROM wipe_target_tenants)'

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
-- ── 运行态：事件 / 出站 / 重试 / 补偿 / 审批 ────────────────────────────
DELETE FROM batch.event_delivery_log        WHERE tenant_id IN :tenants;
DELETE FROM batch.event_outbox_retry        WHERE tenant_id IN :tenants;
DELETE FROM batch.outbox_event              WHERE tenant_id IN :tenants;
DELETE FROM batch.retry_schedule            WHERE tenant_id IN :tenants;
DELETE FROM batch.compensation_command      WHERE tenant_id IN :tenants;
DELETE FROM batch.approval_command          WHERE tenant_id IN :tenants;
DELETE FROM batch.idempotency_record        WHERE tenant_id IN :tenants;
DELETE FROM batch.dead_letter_task          WHERE tenant_id IN :tenants;
-- 删除目标租户回执；其他审计租户的操作保留原目标，以便回查显示待确认。
DELETE FROM batch.config_batch_target_receipt WHERE tenant_id IN :tenants;
-- 清理审计租户的操作时，由外键级联删除该操作的其余目标回执。
DELETE FROM batch.config_batch_operation WHERE audit_tenant_id IN :tenants;

-- ── 文件相关 ────────────────────────────────────────────────────────────
DELETE FROM batch.file_error_record         WHERE tenant_id IN :tenants;
DELETE FROM batch.file_dispatch_record      WHERE tenant_id IN :tenants;
DELETE FROM batch.file_audit_log            WHERE tenant_id IN :tenants;
DELETE FROM batch.file_channel_health       WHERE tenant_id IN :tenants;

-- ── 作业实例链路：task → step → partition → instance ──────────────────
DELETE FROM batch.job_task                  WHERE tenant_id IN :tenants;
DELETE FROM batch.job_step_instance         WHERE tenant_id IN :tenants;
DELETE FROM batch.job_partition             WHERE tenant_id IN :tenants;
-- 热分区可能仍需要 file_record 作为 bundle 输入；清除分区后才可删除源文件行。
DELETE FROM batch.file_record               WHERE tenant_id IN :tenants;

-- ── Workflow 运行实例（workflow_node_run 无 tenant_id，走 workflow_run 子查询） ──
DELETE FROM batch.workflow_node_run
 WHERE workflow_run_id IN (SELECT id FROM batch.workflow_run WHERE tenant_id IN :tenants);
DELETE FROM batch.workflow_run              WHERE tenant_id IN :tenants;

-- 节点已移除后再删分区 JobInstance，避免留下 workflow_node_run.job_instance_id 孤儿。
DELETE FROM batch.job_instance              WHERE tenant_id IN :tenants;

-- ── Pipeline 运行实例（pipeline_step_run 无 tenant_id） ──────────────
DELETE FROM batch.pipeline_progress         WHERE tenant_id IN :tenants;
DELETE FROM batch.pipeline_step_run
 WHERE pipeline_instance_id IN (SELECT id FROM batch.pipeline_instance WHERE tenant_id IN :tenants);
DELETE FROM batch.pipeline_instance         WHERE tenant_id IN :tenants;

-- ── 触发 / 批次日 / 配额状态 / 快照 ──────────────────────────────────
DELETE FROM batch.job_dependency_waiting_launch WHERE tenant_id IN :tenants;
DELETE FROM batch.workflow_launch_wait          WHERE tenant_id IN :tenants;
DELETE FROM batch.batch_day_waiting_launch     WHERE tenant_id IN :tenants;
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
-- 回执删除在两个接收方表清理之后执行（V328 的 NO ACTION 引用）。
DELETE FROM batch.console_push_job_delivery WHERE tenant_id IN :tenants;
DELETE FROM batch.workflow_terminal_notification_receipt WHERE tenant_id IN :tenants;
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

-- ── Job 定义 ──────────────────────────────────────────────────────────
DELETE FROM batch.job_definition            WHERE tenant_id IN :tenants;

-- ── 资源 / 日历 / 窗口 / 配额策略 ───────────────────────────────────
DELETE FROM batch.resource_queue            WHERE tenant_id IN :tenants;
DELETE FROM batch.resource_tag              WHERE tenant_id IN :tenants;
DELETE FROM batch.batch_window              WHERE tenant_id IN :tenants;
DELETE FROM batch.batch_day_catchup_control_event WHERE tenant_id IN :tenants;
DELETE FROM batch.batch_day_catchup_control  WHERE tenant_id IN :tenants;
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
-- DANGER: 批量删除租户及关联数据。执行前必须核对数据库、备份和目标租户清单。

\else
  ROLLBACK;
  \echo '预览完成，未删除任何数据。'
\endif
