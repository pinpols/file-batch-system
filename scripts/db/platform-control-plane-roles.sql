-- 用途：为 Console/Trigger/Orchestrator 的平台库运行时角色授予或撤销最小权限（ARCH-20261103-01）；由 generate-platform-worker-grants.py 生成，勿手改。
-- 用法：psql -X -v ON_ERROR_STOP=1 -v action=grant -v console_role=可信角色 [-v trigger_role=… -v orchestrator_role=…]
--       -f scripts/db/platform-control-plane-roles.sql
-- 只处理传入的角色；每个角色先撤销 batch/archive/quartz 下全部表、序列与函数权限再按清单授予，重复执行收敛到同一状态。
-- 运行时角色不执行 Flyway：迁移由 BATCH_PLATFORM_FLYWAY_USERNAME 指定的 owner 账号执行，每次迁移后重新执行本脚本。
-- Console 角色另需执行 config-cache-invalidation-role.sql 获得跨租户的缓存失效队列策略。action=revoke 时撤销后不再授予。
-- 使用统一入口归属的 Orchestrator 角色须执行 import-ingress-ownership-role.sql 配置只读 RLS 策略。

\set ON_ERROR_STOP on

BEGIN;

\if :{?action}
\else
  DO $$ BEGIN RAISE EXCEPTION '必须通过 action 指定 grant 或 revoke'; END $$;
\endif
SELECT (:'action' = 'grant') AS is_grant, (:'action' = 'revoke') AS is_revoke \gset
\if :is_grant
\elif :is_revoke
\else
  DO $$ BEGIN RAISE EXCEPTION 'action 只能是 grant 或 revoke'; END $$;
\endif

SELECT (:{?console_role} OR :{?trigger_role} OR :{?orchestrator_role}) AS has_any_role \gset
\if :has_any_role
\else
  DO $$ BEGIN RAISE EXCEPTION '至少指定一个 *_role 参数'; END $$;
\endif

-- ===== console =====
\if :{?console_role}
  SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'console_role' AND lower(rolname) <> 'public'
     AND NOT rolsuper AND NOT rolbypassrls) AS role_ok \gset
  \if :role_ok
  \else
    DO $$ BEGIN RAISE EXCEPTION 'console_role 不存在、为 PUBLIC，或具备 SUPERUSER/BYPASSRLS'; END $$;
  \endif
  SELECT format('REVOKE ALL ON ALL TABLES IN SCHEMA batch FROM %I', :'console_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'batch') \gexec
  SELECT format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA batch FROM %I', :'console_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'batch') \gexec
  SELECT format('REVOKE ALL ON ALL FUNCTIONS IN SCHEMA batch FROM %I', :'console_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'batch') \gexec
  SELECT format('REVOKE USAGE ON SCHEMA batch FROM %I', :'console_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'batch') \gexec
  SELECT format('REVOKE ALL ON ALL TABLES IN SCHEMA archive FROM %I', :'console_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'archive') \gexec
  SELECT format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA archive FROM %I', :'console_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'archive') \gexec
  SELECT format('REVOKE ALL ON ALL FUNCTIONS IN SCHEMA archive FROM %I', :'console_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'archive') \gexec
  SELECT format('REVOKE USAGE ON SCHEMA archive FROM %I', :'console_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'archive') \gexec
  SELECT format('REVOKE ALL ON ALL TABLES IN SCHEMA quartz FROM %I', :'console_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'quartz') \gexec
  SELECT format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA quartz FROM %I', :'console_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'quartz') \gexec
  SELECT format('REVOKE ALL ON ALL FUNCTIONS IN SCHEMA quartz FROM %I', :'console_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'quartz') \gexec
  SELECT format('REVOKE USAGE ON SCHEMA quartz FROM %I', :'console_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'quartz') \gexec
  \if :is_grant
    SELECT format('GRANT USAGE ON SCHEMA archive TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SCHEMA batch TO %I', :'console_role') \gexec
    SELECT format('GRANT INSERT, SELECT ON archive.console_operation_audit_archive TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('archive.console_operation_audit_archive', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'archive.console_operation_audit_archive'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT ON archive.console_push_job_delivery_archive TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('archive.console_push_job_delivery_archive', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'archive.console_push_job_delivery_archive'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT ON archive.notification_delivery_log_archive TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('archive.notification_delivery_log_archive', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'archive.notification_delivery_log_archive'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT ON archive.webhook_delivery_log_archive TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('archive.webhook_delivery_log_archive', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'archive.webhook_delivery_log_archive'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT ON archive.workflow_terminal_notification_receipt_archive TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('archive.workflow_terminal_notification_receipt_archive', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'archive.workflow_terminal_notification_receipt_archive'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.alert_escalation_notification_outbox TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.alert_escalation_notification_outbox', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.alert_escalation_notification_outbox'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT, UPDATE ON batch.alert_event TO %I', :'console_role') \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.alert_notification_receipt TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.alert_notification_receipt', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.alert_notification_receipt'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.alert_routing_config TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.alert_routing_config', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.alert_routing_config'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.api_key TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.api_key', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.api_key'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON batch.approval_command TO %I', :'console_role') \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.archive_policy TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.archive_policy', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.archive_policy'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.asset_freshness_policy TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.asset_freshness_policy', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.asset_freshness_policy'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT ON batch.atomic_task_config TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.atomic_task_config', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.atomic_task_config'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.batch_day_catchup_operation TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.batch_day_catchup_operation', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.batch_day_catchup_operation'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON batch.batch_day_instance TO %I', :'console_role') \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.batch_window TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.batch_window', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.batch_window'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.business_calendar TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.business_calendar', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.business_calendar'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.business_shard_catalog TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.business_shard_catalog', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.business_shard_catalog'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.business_tenant_placement TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.business_tenant_placement', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.business_tenant_placement'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.calendar_holiday TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.calendar_holiday', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.calendar_holiday'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.config_approval TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.config_approval', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.config_approval'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT ON batch.config_batch_operation TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.config_batch_operation', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.config_batch_operation'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT ON batch.config_batch_target_receipt TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.config_batch_target_receipt', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.config_batch_target_receipt'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.config_cache_invalidation_outbox TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.config_cache_invalidation_outbox', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.config_cache_invalidation_outbox'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT ON batch.config_change_log TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.config_change_log', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.config_change_log'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.config_release TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.config_release', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.config_release'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.config_sync_log TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.config_sync_log', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.config_sync_log'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT ON batch.config_sync_target_receipt TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.config_sync_target_receipt', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.config_sync_target_receipt'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.console_ai_attachment TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.console_ai_attachment', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.console_ai_attachment'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT ON batch.console_ai_audit_log TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.console_ai_audit_log', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.console_ai_audit_log'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.console_ai_conversation TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.console_ai_conversation', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.console_ai_conversation'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.console_ai_monthly_usage TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.console_ai_monthly_usage', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.console_ai_monthly_usage'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.console_ai_object_cleanup TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.console_ai_object_cleanup', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.console_ai_object_cleanup'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.console_ai_turn TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.console_ai_turn', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.console_ai_turn'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT ON batch.console_external_identity TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.console_external_identity', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.console_external_identity'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT, UPDATE ON batch.console_maintenance_state TO %I', :'console_role') \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT ON batch.console_operation_audit TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.console_operation_audit', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.console_operation_audit'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT ON batch.console_push_approval_notification TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.console_push_approval_notification', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.console_push_approval_notification'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.console_push_job_delivery TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.console_push_job_delivery', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.console_push_job_delivery'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.console_push_subscription TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.console_push_subscription', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.console_push_subscription'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.console_usage_daily TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.console_usage_daily', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.console_usage_daily'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.console_user_account TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.console_user_account', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.console_user_account'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT ON batch.console_user_batch_operation TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.console_user_batch_operation', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.console_user_batch_operation'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON batch.custom_task_type_registry TO %I', :'console_role') \gexec
    SELECT format('GRANT SELECT ON batch.dead_letter_task TO %I', :'console_role') \gexec
    SELECT format('GRANT SELECT ON batch.event_delivery_log TO %I', :'console_role') \gexec
    SELECT format('GRANT SELECT ON batch.event_outbox_retry TO %I', :'console_role') \gexec
    SELECT format('GRANT SELECT ON batch.external_event_expectation_definition TO %I', :'console_role') \gexec
    SELECT format('GRANT SELECT ON batch.file_audit_log TO %I', :'console_role') \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.file_channel_config TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.file_channel_config', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.file_channel_config'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON batch.file_dispatch_record TO %I', :'console_role') \gexec
    SELECT format('GRANT SELECT ON batch.file_error_record TO %I', :'console_role') \gexec
    SELECT format('GRANT SELECT ON batch.file_group_expectation_definition TO %I', :'console_role') \gexec
    SELECT format('GRANT SELECT ON batch.file_record TO %I', :'console_role') \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.file_template_config TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.file_template_config', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.file_template_config'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT ON batch.idempotency_record TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.idempotency_record', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.idempotency_record'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.job_definition TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.job_definition', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.job_definition'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT ON batch.job_dependency TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.job_dependency', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.job_dependency'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON batch.job_execution_log TO %I', :'console_role') \gexec
    SELECT format('GRANT SELECT ON batch.job_instance TO %I', :'console_role') \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.job_monitoring_policy TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.job_monitoring_policy', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.job_monitoring_policy'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON batch.job_partition TO %I', :'console_role') \gexec
    SELECT format('GRANT SELECT ON batch.job_step_instance TO %I', :'console_role') \gexec
    SELECT format('GRANT SELECT ON batch.job_task TO %I', :'console_role') \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.notification_channel TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.notification_channel', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.notification_channel'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.notification_delivery_log TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.notification_delivery_log', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.notification_delivery_log'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON batch.outbox_event TO %I', :'console_role') \gexec
    SELECT format('GRANT SELECT ON batch.pipeline_instance TO %I', :'console_role') \gexec
    SELECT format('GRANT SELECT ON batch.pipeline_progress TO %I', :'console_role') \gexec
    SELECT format('GRANT SELECT ON batch.pipeline_step_run TO %I', :'console_role') \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.resource_queue TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.resource_queue', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.resource_queue'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.resource_tag TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.resource_tag', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.resource_tag'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON batch.retry_schedule TO %I', :'console_role') \gexec
    SELECT format('GRANT SELECT ON batch.scheduled_occurrence TO %I', :'console_role') \gexec
    SELECT format('GRANT SELECT ON batch.scheduled_occurrence_coverage TO %I', :'console_role') \gexec
    SELECT format('GRANT SELECT ON batch.scheduled_occurrence_fire TO %I', :'console_role') \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.secret_version TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.secret_version', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.secret_version'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON batch.shedlock TO %I', :'console_role') \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.stateful_backend_binding TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.stateful_backend_binding', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.stateful_backend_binding'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT ON batch.stateful_backend_cutover_history TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.stateful_backend_cutover_history', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.stateful_backend_cutover_history'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON batch.step_registry TO %I', :'console_role') \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.subscription_rule TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.subscription_rule', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.subscription_rule'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.system_parameter TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.system_parameter', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.system_parameter'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.tenant TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.tenant', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.tenant'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON batch.tenant_configuration_revision TO %I', :'console_role') \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.tenant_quota_policy TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.tenant_quota_policy', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.tenant_quota_policy'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON batch.trigger_request TO %I', :'console_role') \gexec
    SELECT format('GRANT SELECT ON batch.trigger_request_idempotency_alias TO %I', :'console_role') \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.webhook_delivery_log TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.webhook_delivery_log', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.webhook_delivery_log'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.webhook_subscription TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.webhook_subscription', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.webhook_subscription'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON batch.worker_registry TO %I', :'console_role') \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.workflow_definition TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.workflow_definition', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.workflow_definition'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT ON batch.workflow_definition_version TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.workflow_definition_version', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.workflow_definition_version'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.workflow_edge TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.workflow_edge', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.workflow_edge'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.workflow_monitoring_policy TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.workflow_monitoring_policy', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.workflow_monitoring_policy'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.workflow_node TO %I', :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.workflow_node', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.workflow_node'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON batch.workflow_node_run TO %I', :'console_role') \gexec
    SELECT format('GRANT SELECT ON batch.workflow_run TO %I', :'console_role') \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT ON batch.workflow_terminal_notification_receipt TO %I', :'console_role') \gexec
    SELECT format('GRANT UPDATE (%I) ON batch.workflow_terminal_notification_receipt TO %I', COALESCE(
        (SELECT attname FROM pg_attribute WHERE attrelid = 'batch.workflow_terminal_notification_receipt'::regclass
            AND attname = 'updated_at' AND NOT attisdropped),
        (SELECT a.attname FROM pg_index i JOIN pg_attribute a
            ON a.attrelid = i.indrelid AND a.attnum = i.indkey[0]
          WHERE i.indrelid = 'batch.workflow_terminal_notification_receipt'::regclass AND i.indisprimary)), :'console_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'console_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.workflow_terminal_notification_receipt', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.workflow_terminal_notification_receipt'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT EXECUTE ON FUNCTION %s TO %I', p.oid::regprocedure, :'console_role')
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'batch' AND p.proname = 'current_tenant_configuration_revision' \gexec
    SELECT format('GRANT EXECUTE ON FUNCTION %s TO %I', p.oid::regprocedure, :'console_role')
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'batch' AND p.proname = 'lock_tenant_configuration_revision' \gexec
  \endif
\endif

-- ===== trigger =====
\if :{?trigger_role}
  SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'trigger_role' AND lower(rolname) <> 'public'
     AND NOT rolsuper AND NOT rolbypassrls) AS role_ok \gset
  \if :role_ok
  \else
    DO $$ BEGIN RAISE EXCEPTION 'trigger_role 不存在、为 PUBLIC，或具备 SUPERUSER/BYPASSRLS'; END $$;
  \endif
  SELECT format('REVOKE ALL ON ALL TABLES IN SCHEMA batch FROM %I', :'trigger_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'batch') \gexec
  SELECT format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA batch FROM %I', :'trigger_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'batch') \gexec
  SELECT format('REVOKE ALL ON ALL FUNCTIONS IN SCHEMA batch FROM %I', :'trigger_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'batch') \gexec
  SELECT format('REVOKE USAGE ON SCHEMA batch FROM %I', :'trigger_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'batch') \gexec
  SELECT format('REVOKE ALL ON ALL TABLES IN SCHEMA archive FROM %I', :'trigger_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'archive') \gexec
  SELECT format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA archive FROM %I', :'trigger_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'archive') \gexec
  SELECT format('REVOKE ALL ON ALL FUNCTIONS IN SCHEMA archive FROM %I', :'trigger_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'archive') \gexec
  SELECT format('REVOKE USAGE ON SCHEMA archive FROM %I', :'trigger_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'archive') \gexec
  SELECT format('REVOKE ALL ON ALL TABLES IN SCHEMA quartz FROM %I', :'trigger_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'quartz') \gexec
  SELECT format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA quartz FROM %I', :'trigger_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'quartz') \gexec
  SELECT format('REVOKE ALL ON ALL FUNCTIONS IN SCHEMA quartz FROM %I', :'trigger_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'quartz') \gexec
  SELECT format('REVOKE USAGE ON SCHEMA quartz FROM %I', :'trigger_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'quartz') \gexec
  \if :is_grant
    SELECT format('GRANT USAGE ON SCHEMA batch TO %I', :'trigger_role') \gexec
    SELECT format('GRANT USAGE ON SCHEMA quartz TO %I', :'trigger_role') \gexec
    SELECT format('GRANT SELECT, UPDATE ON batch.api_key TO %I', :'trigger_role') \gexec
    SELECT format('GRANT SELECT ON batch.approval_command TO %I', :'trigger_role') \gexec
    SELECT format('GRANT SELECT ON batch.batch_day_instance TO %I', :'trigger_role') \gexec
    SELECT format('GRANT SELECT ON batch.business_calendar TO %I', :'trigger_role') \gexec
    SELECT format('GRANT SELECT ON batch.business_tenant_placement TO %I', :'trigger_role') \gexec
    SELECT format('GRANT SELECT ON batch.calendar_holiday TO %I', :'trigger_role') \gexec
    SELECT format('GRANT INSERT, SELECT ON batch.external_event_delivery TO %I', :'trigger_role') \gexec
    SELECT format('GRANT UPDATE (%I) ON batch.external_event_delivery TO %I', COALESCE(
        (SELECT attname FROM pg_attribute WHERE attrelid = 'batch.external_event_delivery'::regclass
            AND attname = 'updated_at' AND NOT attisdropped),
        (SELECT a.attname FROM pg_index i JOIN pg_attribute a
            ON a.attrelid = i.indrelid AND a.attnum = i.indkey[0]
          WHERE i.indrelid = 'batch.external_event_delivery'::regclass AND i.indisprimary)), :'trigger_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'trigger_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.external_event_delivery', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.external_event_delivery'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT ON batch.external_event_delivery_alias TO %I', :'trigger_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'trigger_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.external_event_delivery_alias', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.external_event_delivery_alias'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON batch.external_event_expectation_definition TO %I', :'trigger_role') \gexec
    SELECT format('GRANT UPDATE (%I) ON batch.external_event_expectation_definition TO %I', COALESCE(
        (SELECT attname FROM pg_attribute WHERE attrelid = 'batch.external_event_expectation_definition'::regclass
            AND attname = 'updated_at' AND NOT attisdropped),
        (SELECT a.attname FROM pg_index i JOIN pg_attribute a
            ON a.attrelid = i.indrelid AND a.attnum = i.indkey[0]
          WHERE i.indrelid = 'batch.external_event_expectation_definition'::regclass AND i.indisprimary)), :'trigger_role') \gexec
    SELECT format('GRANT SELECT ON batch.job_definition TO %I', :'trigger_role') \gexec
    SELECT format('GRANT SELECT ON batch.job_dependency TO %I', :'trigger_role') \gexec
    SELECT format('GRANT SELECT ON batch.job_instance TO %I', :'trigger_role') \gexec
    SELECT format('GRANT UPDATE (%I) ON batch.job_instance TO %I', COALESCE(
        (SELECT attname FROM pg_attribute WHERE attrelid = 'batch.job_instance'::regclass
            AND attname = 'updated_at' AND NOT attisdropped),
        (SELECT a.attname FROM pg_index i JOIN pg_attribute a
            ON a.attrelid = i.indrelid AND a.attnum = i.indkey[0]
          WHERE i.indrelid = 'batch.job_instance'::regclass AND i.indisprimary)), :'trigger_role') \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.scheduled_occurrence TO %I', :'trigger_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'trigger_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.scheduled_occurrence', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.scheduled_occurrence'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT ON batch.scheduled_occurrence_catch_up_audit TO %I', :'trigger_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'trigger_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.scheduled_occurrence_catch_up_audit', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.scheduled_occurrence_catch_up_audit'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.scheduled_occurrence_coverage TO %I', :'trigger_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'trigger_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.scheduled_occurrence_coverage', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.scheduled_occurrence_coverage'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT ON batch.scheduled_occurrence_fire TO %I', :'trigger_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'trigger_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.scheduled_occurrence_fire', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.scheduled_occurrence_fire'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.shedlock TO %I', :'trigger_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'trigger_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.shedlock', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.shedlock'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.stateful_backend_binding TO %I', :'trigger_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'trigger_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.stateful_backend_binding', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.stateful_backend_binding'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT ON batch.stateful_backend_cutover_history TO %I', :'trigger_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'trigger_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.stateful_backend_cutover_history', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.stateful_backend_cutover_history'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON batch.tenant TO %I', :'trigger_role') \gexec
    SELECT format('GRANT SELECT ON batch.tenant_configuration_revision TO %I', :'trigger_role') \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.trigger_misfire_pending TO %I', :'trigger_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'trigger_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.trigger_misfire_pending', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.trigger_misfire_pending'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.trigger_outbox_event TO %I', :'trigger_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'trigger_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.trigger_outbox_event', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.trigger_outbox_event'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.trigger_request TO %I', :'trigger_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'trigger_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.trigger_request', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.trigger_request'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT ON batch.trigger_request_idempotency_alias TO %I', :'trigger_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'trigger_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.trigger_request_idempotency_alias', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.trigger_request_idempotency_alias'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT, UPDATE ON batch.workflow_definition TO %I', :'trigger_role') \gexec
    SELECT format('GRANT SELECT ON batch.workflow_run TO %I', :'trigger_role') \gexec
    SELECT format('GRANT UPDATE (%I) ON batch.workflow_run TO %I', COALESCE(
        (SELECT attname FROM pg_attribute WHERE attrelid = 'batch.workflow_run'::regclass
            AND attname = 'updated_at' AND NOT attisdropped),
        (SELECT a.attname FROM pg_index i JOIN pg_attribute a
            ON a.attrelid = i.indrelid AND a.attnum = i.indkey[0]
          WHERE i.indrelid = 'batch.workflow_run'::regclass AND i.indisprimary)), :'trigger_role') \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON quartz.qrtz_blob_triggers TO %I', :'trigger_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'trigger_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('quartz.qrtz_blob_triggers', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'quartz.qrtz_blob_triggers'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON quartz.qrtz_calendars TO %I', :'trigger_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'trigger_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('quartz.qrtz_calendars', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'quartz.qrtz_calendars'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON quartz.qrtz_cron_triggers TO %I', :'trigger_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'trigger_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('quartz.qrtz_cron_triggers', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'quartz.qrtz_cron_triggers'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON quartz.qrtz_fired_triggers TO %I', :'trigger_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'trigger_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('quartz.qrtz_fired_triggers', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'quartz.qrtz_fired_triggers'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON quartz.qrtz_job_details TO %I', :'trigger_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'trigger_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('quartz.qrtz_job_details', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'quartz.qrtz_job_details'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON quartz.qrtz_locks TO %I', :'trigger_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'trigger_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('quartz.qrtz_locks', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'quartz.qrtz_locks'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON quartz.qrtz_paused_trigger_grps TO %I', :'trigger_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'trigger_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('quartz.qrtz_paused_trigger_grps', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'quartz.qrtz_paused_trigger_grps'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON quartz.qrtz_scheduler_state TO %I', :'trigger_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'trigger_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('quartz.qrtz_scheduler_state', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'quartz.qrtz_scheduler_state'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON quartz.qrtz_simple_triggers TO %I', :'trigger_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'trigger_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('quartz.qrtz_simple_triggers', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'quartz.qrtz_simple_triggers'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON quartz.qrtz_simprop_triggers TO %I', :'trigger_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'trigger_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('quartz.qrtz_simprop_triggers', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'quartz.qrtz_simprop_triggers'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON quartz.qrtz_triggers TO %I', :'trigger_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'trigger_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('quartz.qrtz_triggers', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'quartz.qrtz_triggers'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT EXECUTE ON FUNCTION %s TO %I', p.oid::regprocedure, :'trigger_role')
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'batch' AND p.proname = 'current_tenant_configuration_revision' \gexec
    SELECT format('GRANT EXECUTE ON FUNCTION %s TO %I', p.oid::regprocedure, :'trigger_role')
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'batch' AND p.proname = 'lock_tenant_configuration_revision' \gexec
  \endif
\endif

-- ===== orchestrator =====
\if :{?orchestrator_role}
  SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'orchestrator_role' AND lower(rolname) <> 'public'
     AND NOT rolsuper AND NOT rolbypassrls) AS role_ok \gset
  \if :role_ok
  \else
    DO $$ BEGIN RAISE EXCEPTION 'orchestrator_role 不存在、为 PUBLIC，或具备 SUPERUSER/BYPASSRLS'; END $$;
  \endif
  SELECT format('REVOKE ALL ON ALL TABLES IN SCHEMA batch FROM %I', :'orchestrator_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'batch') \gexec
  SELECT format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA batch FROM %I', :'orchestrator_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'batch') \gexec
  SELECT format('REVOKE ALL ON ALL FUNCTIONS IN SCHEMA batch FROM %I', :'orchestrator_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'batch') \gexec
  SELECT format('REVOKE USAGE ON SCHEMA batch FROM %I', :'orchestrator_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'batch') \gexec
  SELECT format('REVOKE ALL ON ALL TABLES IN SCHEMA archive FROM %I', :'orchestrator_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'archive') \gexec
  SELECT format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA archive FROM %I', :'orchestrator_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'archive') \gexec
  SELECT format('REVOKE ALL ON ALL FUNCTIONS IN SCHEMA archive FROM %I', :'orchestrator_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'archive') \gexec
  SELECT format('REVOKE USAGE ON SCHEMA archive FROM %I', :'orchestrator_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'archive') \gexec
  SELECT format('REVOKE ALL ON ALL TABLES IN SCHEMA quartz FROM %I', :'orchestrator_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'quartz') \gexec
  SELECT format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA quartz FROM %I', :'orchestrator_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'quartz') \gexec
  SELECT format('REVOKE ALL ON ALL FUNCTIONS IN SCHEMA quartz FROM %I', :'orchestrator_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'quartz') \gexec
  SELECT format('REVOKE USAGE ON SCHEMA quartz FROM %I', :'orchestrator_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'quartz') \gexec
  \if :is_grant
    SELECT format('GRANT USAGE ON SCHEMA archive TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SCHEMA batch TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT SELECT ON archive.batch_day_replay_entry_archive TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT SELECT ON archive.batch_day_replay_session_archive TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT INSERT, SELECT ON archive.compensation_command_archive TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('archive.compensation_command_archive', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'archive.compensation_command_archive'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON archive.console_operation_audit_archive TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT SELECT ON archive.console_push_job_delivery_archive TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT INSERT, SELECT ON archive.data_quality_check_archive TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('archive.data_quality_check_archive', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'archive.data_quality_check_archive'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON archive.dead_letter_task_archive TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT INSERT, SELECT ON archive.event_delivery_log_archive TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('archive.event_delivery_log_archive', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'archive.event_delivery_log_archive'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT ON archive.event_outbox_retry_archive TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('archive.event_outbox_retry_archive', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'archive.event_outbox_retry_archive'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT ON archive.file_dispatch_record_archive TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('archive.file_dispatch_record_archive', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'archive.file_dispatch_record_archive'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT ON archive.file_record_archive TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('archive.file_record_archive', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'archive.file_record_archive'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT ON archive.job_execution_log_archive TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('archive.job_execution_log_archive', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'archive.job_execution_log_archive'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT ON archive.job_instance_archive TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('archive.job_instance_archive', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'archive.job_instance_archive'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT ON archive.job_partition_archive TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('archive.job_partition_archive', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'archive.job_partition_archive'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT ON archive.job_step_instance_archive TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('archive.job_step_instance_archive', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'archive.job_step_instance_archive'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT ON archive.job_task_archive TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('archive.job_task_archive', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'archive.job_task_archive'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON archive.notification_delivery_log_archive TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT INSERT, SELECT ON archive.outbox_event_archive TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('archive.outbox_event_archive', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'archive.outbox_event_archive'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT ON archive.pipeline_instance_archive TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('archive.pipeline_instance_archive', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'archive.pipeline_instance_archive'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT ON archive.pipeline_progress_archive TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('archive.pipeline_progress_archive', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'archive.pipeline_progress_archive'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT ON archive.pipeline_step_run_archive TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('archive.pipeline_step_run_archive', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'archive.pipeline_step_run_archive'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON archive.result_version_archive TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('archive.result_version_archive', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'archive.result_version_archive'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON archive.trigger_outbox_event_archive TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT SELECT ON archive.webhook_delivery_log_archive TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT INSERT, SELECT ON archive.workflow_node_run_archive TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('archive.workflow_node_run_archive', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'archive.workflow_node_run_archive'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT ON archive.workflow_run_archive TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('archive.workflow_run_archive', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'archive.workflow_run_archive'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON archive.workflow_terminal_notification_receipt_archive TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.alert_event TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.alert_event', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.alert_event'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON batch.alert_notification_receipt TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT DELETE, SELECT ON batch.alert_routing_config TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT DELETE, SELECT, UPDATE ON batch.api_key TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.approval_command TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.approval_command', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.approval_command'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, SELECT ON batch.archive_policy TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT SELECT ON batch.asset_freshness_policy TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.asset_partition TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.asset_partition', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.asset_partition'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.batch_day_catchup_control TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.batch_day_catchup_control', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.batch_day_catchup_control'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT ON batch.batch_day_catchup_control_event TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.batch_day_catchup_control_event', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.batch_day_catchup_control_event'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.batch_day_instance TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.batch_day_instance', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.batch_day_instance'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.batch_day_open_cursor TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.batch_day_open_cursor', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.batch_day_open_cursor'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT ON batch.batch_day_operation_audit TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.batch_day_operation_audit', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.batch_day_operation_audit'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.batch_day_replay_entry TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.batch_day_replay_entry', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.batch_day_replay_entry'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.batch_day_replay_preview_token TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.batch_day_replay_preview_token', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.batch_day_replay_preview_token'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.batch_day_replay_session TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.batch_day_replay_session', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.batch_day_replay_session'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.batch_day_waiting_launch TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.batch_day_waiting_launch', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.batch_day_waiting_launch'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON batch.batch_window TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT SELECT ON batch.business_calendar TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT SELECT ON batch.business_tenant_placement TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT INSERT, SELECT ON batch.calendar_dependency TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.calendar_dependency', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.calendar_dependency'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.calendar_dependency_wait TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.calendar_dependency_wait', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.calendar_dependency_wait'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON batch.calendar_holiday TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.compensation_command TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.compensation_command', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.compensation_command'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON batch.console_operation_audit TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT SELECT ON batch.console_push_job_delivery TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT DELETE, SELECT ON batch.console_user_account TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.custom_task_type_registry TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.custom_task_type_registry', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.custom_task_type_registry'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.data_asset TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.data_asset', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.data_asset'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT ON batch.data_quality_check TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT UPDATE (%I) ON batch.data_quality_check TO %I', COALESCE(
        (SELECT attname FROM pg_attribute WHERE attrelid = 'batch.data_quality_check'::regclass
            AND attname = 'updated_at' AND NOT attisdropped),
        (SELECT a.attname FROM pg_index i JOIN pg_attribute a
            ON a.attrelid = i.indrelid AND a.attnum = i.indkey[0]
          WHERE i.indrelid = 'batch.data_quality_check'::regclass AND i.indisprimary)), :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.data_quality_check', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.data_quality_check'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.data_quality_rule TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.data_quality_rule', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.data_quality_rule'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.dead_letter_task TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.dead_letter_task', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.dead_letter_task'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT ON batch.disaster_day_override TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.disaster_day_override', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.disaster_day_override'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT ON batch.event_delivery_log TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.event_delivery_log', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.event_delivery_log'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT ON batch.event_outbox_retry TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.event_outbox_retry', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.event_outbox_retry'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.expected_work_occurrence TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.expected_work_occurrence', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.expected_work_occurrence'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.external_event_expectation_definition TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.external_event_expectation_definition', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.external_event_expectation_definition'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT ON batch.file_audit_log TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.file_audit_log', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.file_audit_log'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, SELECT ON batch.file_channel_config TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT SELECT ON batch.file_channel_health TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT DELETE, SELECT, UPDATE ON batch.file_dispatch_record TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT DELETE, SELECT ON batch.file_error_record TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.file_governance_reconcile_cursor TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.file_governance_reconcile_cursor', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.file_governance_reconcile_cursor'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.file_group_expectation_definition TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.file_group_expectation_definition', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.file_group_expectation_definition'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.file_record TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.file_record', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.file_record'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, SELECT ON batch.file_template_config TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.forensic_export_log TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.forensic_export_log', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.forensic_export_log'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.idempotency_record TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.idempotency_record', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.idempotency_record'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON batch.import_ingress_ownership TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT SELECT ON batch.import_ingress_ownership_binding TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT DELETE, SELECT ON batch.job_definition TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT SELECT ON batch.job_dependency TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.job_dependency_waiting_launch TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.job_dependency_waiting_launch', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.job_dependency_waiting_launch'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT ON batch.job_execution_log TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT UPDATE (%I) ON batch.job_execution_log TO %I', COALESCE(
        (SELECT attname FROM pg_attribute WHERE attrelid = 'batch.job_execution_log'::regclass
            AND attname = 'updated_at' AND NOT attisdropped),
        (SELECT a.attname FROM pg_index i JOIN pg_attribute a
            ON a.attrelid = i.indrelid AND a.attnum = i.indkey[0]
          WHERE i.indrelid = 'batch.job_execution_log'::regclass AND i.indisprimary)), :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.job_execution_log', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.job_execution_log'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.job_instance TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.job_instance', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.job_instance'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT ON batch.job_instance_dedup_key TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.job_instance_dedup_key', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.job_instance_dedup_key'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT ON batch.job_monitoring_alert_claim TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.job_monitoring_alert_claim', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.job_monitoring_alert_claim'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON batch.job_monitoring_policy TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT UPDATE (%I) ON batch.job_monitoring_policy TO %I', COALESCE(
        (SELECT attname FROM pg_attribute WHERE attrelid = 'batch.job_monitoring_policy'::regclass
            AND attname = 'updated_at' AND NOT attisdropped),
        (SELECT a.attname FROM pg_index i JOIN pg_attribute a
            ON a.attrelid = i.indrelid AND a.attnum = i.indkey[0]
          WHERE i.indrelid = 'batch.job_monitoring_policy'::regclass AND i.indisprimary)), :'orchestrator_role') \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.job_partition TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.job_partition', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.job_partition'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.job_step_instance TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.job_step_instance', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.job_step_instance'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.job_task TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.job_task', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.job_task'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON batch.notification_delivery_log TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.outbox_event TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.outbox_event', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.outbox_event'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT ON batch.outbox_event_dedup_key TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.outbox_event_dedup_key', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.outbox_event_dedup_key'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, SELECT, UPDATE ON batch.pipeline_instance TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT DELETE, SELECT ON batch.pipeline_progress TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT UPDATE (%I) ON batch.pipeline_progress TO %I', COALESCE(
        (SELECT attname FROM pg_attribute WHERE attrelid = 'batch.pipeline_progress'::regclass
            AND attname = 'updated_at' AND NOT attisdropped),
        (SELECT a.attname FROM pg_index i JOIN pg_attribute a
            ON a.attrelid = i.indrelid AND a.attnum = i.indkey[0]
          WHERE i.indrelid = 'batch.pipeline_progress'::regclass AND i.indisprimary)), :'orchestrator_role') \gexec
    SELECT format('GRANT DELETE, SELECT, UPDATE ON batch.pipeline_step_run TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.quota_runtime_state TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.quota_runtime_state', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.quota_runtime_state'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.readiness_wait TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.readiness_wait', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.readiness_wait'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.replay_operation TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.replay_operation', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.replay_operation'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.replay_operation_preview_token TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.replay_operation_preview_token', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.replay_operation_preview_token'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON batch.resource_queue TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.result_version TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.result_version', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.result_version'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.retry_schedule TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.retry_schedule', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.retry_schedule'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, SELECT ON batch.scheduled_occurrence TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT DELETE, SELECT ON batch.scheduled_occurrence_catch_up_audit TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT DELETE, SELECT ON batch.scheduled_occurrence_coverage TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT SELECT ON batch.secret_version TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.shedlock TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.shedlock', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.shedlock'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.stateful_backend_binding TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.stateful_backend_binding', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.stateful_backend_binding'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT ON batch.stateful_backend_cutover_history TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.stateful_backend_cutover_history', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.stateful_backend_cutover_history'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON batch.system_parameter TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT DELETE, SELECT ON batch.tenant TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT SELECT ON batch.tenant_configuration_revision TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT DELETE, SELECT ON batch.tenant_quota_policy TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT INSERT, SELECT ON batch.tenant_scheduler_snapshot TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.tenant_scheduler_snapshot', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.tenant_scheduler_snapshot'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.trigger_launch_recovery TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.trigger_launch_recovery', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.trigger_launch_recovery'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, SELECT ON batch.trigger_misfire_pending TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT DELETE, SELECT ON batch.trigger_outbox_event TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.trigger_request TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.trigger_request', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.trigger_request'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT, UPDATE ON batch.waiting_partition_scan_cursor TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT SELECT ON batch.webhook_delivery_log TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.worker_registry TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.worker_registry', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.worker_registry'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, SELECT ON batch.workflow_definition TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT DELETE, SELECT ON batch.workflow_edge TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.workflow_launch_wait TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.workflow_launch_wait', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.workflow_launch_wait'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON batch.workflow_monitoring_policy TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT UPDATE (%I) ON batch.workflow_monitoring_policy TO %I', COALESCE(
        (SELECT attname FROM pg_attribute WHERE attrelid = 'batch.workflow_monitoring_policy'::regclass
            AND attname = 'updated_at' AND NOT attisdropped),
        (SELECT a.attname FROM pg_index i JOIN pg_attribute a
            ON a.attrelid = i.indrelid AND a.attnum = i.indkey[0]
          WHERE i.indrelid = 'batch.workflow_monitoring_policy'::regclass AND i.indisprimary)), :'orchestrator_role') \gexec
    SELECT format('GRANT DELETE, SELECT ON batch.workflow_node TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.workflow_node_run TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.workflow_node_run', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.workflow_node_run'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.workflow_run TO %I', :'orchestrator_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'orchestrator_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.workflow_run', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.workflow_run'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON batch.workflow_terminal_notification_receipt TO %I', :'orchestrator_role') \gexec
  \endif
\endif

COMMIT;
