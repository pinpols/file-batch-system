-- 用途：为内建 Worker 的平台库角色授予或撤销最小权限（ARCH-20261103-01）；由 generate-platform-worker-grants.py 生成，勿手改。
-- 用法：psql -X -v ON_ERROR_STOP=1 -v action=grant -v import_role=可信角色 [-v export_role=… -v process_role=…
--       -v dispatch_role=… -v atomic_role=…] -f scripts/db/platform-worker-roles.sql
-- 只处理传入的角色；每个角色先撤销 batch/archive 下全部表与序列权限再按清单授予，重复执行收敛到同一状态。
-- 脚本不创建账号、不改 schema，也不调整 Orchestrator/Console/Trigger 角色。action=revoke 时撤销后不再授予。
-- Import 角色还须执行 import-ingress-scan-role.sql 配置入口扫描协调状态的跨租户 RLS 策略。
-- 使用统一入口归属的 Import 角色须执行 import-ingress-ownership-role.sql 配置只读 RLS 策略。

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

SELECT (:{?import_role} OR :{?export_role} OR :{?process_role} OR :{?dispatch_role} OR :{?atomic_role}) AS has_any_role \gset
\if :has_any_role
\else
  DO $$ BEGIN RAISE EXCEPTION '至少指定一个 *_role 参数'; END $$;
\endif

-- ===== import Worker =====
\if :{?import_role}
  SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'import_role' AND lower(rolname) <> 'public'
     AND NOT rolsuper AND NOT rolbypassrls) AS role_ok \gset
  \if :role_ok
  \else
    DO $$ BEGIN RAISE EXCEPTION 'import_role 不存在、为 PUBLIC，或具备 SUPERUSER/BYPASSRLS'; END $$;
  \endif
  SELECT format('REVOKE ALL ON ALL TABLES IN SCHEMA batch FROM %I', :'import_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'batch') \gexec
  SELECT format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA batch FROM %I', :'import_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'batch') \gexec
  SELECT format('REVOKE USAGE ON SCHEMA batch FROM %I', :'import_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'batch') \gexec
  SELECT format('REVOKE ALL ON ALL TABLES IN SCHEMA archive FROM %I', :'import_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'archive') \gexec
  SELECT format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA archive FROM %I', :'import_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'archive') \gexec
  SELECT format('REVOKE USAGE ON SCHEMA archive FROM %I', :'import_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'archive') \gexec
  \if :is_grant
    SELECT format('GRANT USAGE ON SCHEMA batch TO %I', :'import_role') \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.biz_table_schema TO %I', :'import_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'import_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.biz_table_schema', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.biz_table_schema'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON batch.business_tenant_placement TO %I', :'import_role') \gexec
    SELECT format('GRANT INSERT, SELECT ON batch.file_audit_log TO %I', :'import_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'import_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.file_audit_log', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.file_audit_log'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT ON batch.file_error_record TO %I', :'import_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'import_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.file_error_record', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.file_error_record'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.file_record TO %I', :'import_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'import_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.file_record', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.file_record'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON batch.file_template_config TO %I', :'import_role') \gexec
    SELECT format('GRANT SELECT ON batch.import_ingress_ownership TO %I', :'import_role') \gexec
    SELECT format('GRANT SELECT ON batch.import_ingress_ownership_binding TO %I', :'import_role') \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.import_ingress_scan_object TO %I', :'import_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'import_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.import_ingress_scan_object', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.import_ingress_scan_object'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.import_ingress_scan_state TO %I', :'import_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'import_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.import_ingress_scan_state', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.import_ingress_scan_state'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.import_ingress_scan_storage_lease TO %I', :'import_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'import_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.import_ingress_scan_storage_lease', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.import_ingress_scan_storage_lease'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON batch.job_instance TO %I', :'import_role') \gexec
    SELECT format('GRANT UPDATE (%I) ON batch.job_instance TO %I', COALESCE(
        (SELECT attname FROM pg_attribute WHERE attrelid = 'batch.job_instance'::regclass
            AND attname = 'updated_at' AND NOT attisdropped),
        (SELECT a.attname FROM pg_index i JOIN pg_attribute a
            ON a.attrelid = i.indrelid AND a.attnum = i.indkey[0]
          WHERE i.indrelid = 'batch.job_instance'::regclass AND i.indisprimary)), :'import_role') \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.pipeline_instance TO %I', :'import_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'import_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.pipeline_instance', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.pipeline_instance'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.pipeline_progress TO %I', :'import_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'import_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.pipeline_progress', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.pipeline_progress'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.pipeline_step_run TO %I', :'import_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'import_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.pipeline_step_run', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.pipeline_step_run'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.stateful_backend_binding TO %I', :'import_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'import_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.stateful_backend_binding', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.stateful_backend_binding'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT ON batch.stateful_backend_cutover_history TO %I', :'import_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'import_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.stateful_backend_cutover_history', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.stateful_backend_cutover_history'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT ON batch.step_registry TO %I', :'import_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'import_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.step_registry', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.step_registry'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.worker_report_outbox TO %I', :'import_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'import_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.worker_report_outbox', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.worker_report_outbox'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
  \endif
\endif

-- ===== export Worker =====
\if :{?export_role}
  SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'export_role' AND lower(rolname) <> 'public'
     AND NOT rolsuper AND NOT rolbypassrls) AS role_ok \gset
  \if :role_ok
  \else
    DO $$ BEGIN RAISE EXCEPTION 'export_role 不存在、为 PUBLIC，或具备 SUPERUSER/BYPASSRLS'; END $$;
  \endif
  SELECT format('REVOKE ALL ON ALL TABLES IN SCHEMA batch FROM %I', :'export_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'batch') \gexec
  SELECT format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA batch FROM %I', :'export_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'batch') \gexec
  SELECT format('REVOKE USAGE ON SCHEMA batch FROM %I', :'export_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'batch') \gexec
  SELECT format('REVOKE ALL ON ALL TABLES IN SCHEMA archive FROM %I', :'export_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'archive') \gexec
  SELECT format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA archive FROM %I', :'export_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'archive') \gexec
  SELECT format('REVOKE USAGE ON SCHEMA archive FROM %I', :'export_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'archive') \gexec
  \if :is_grant
    SELECT format('GRANT USAGE ON SCHEMA batch TO %I', :'export_role') \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.biz_table_schema TO %I', :'export_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'export_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.biz_table_schema', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.biz_table_schema'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON batch.business_tenant_placement TO %I', :'export_role') \gexec
    SELECT format('GRANT INSERT, SELECT ON batch.file_audit_log TO %I', :'export_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'export_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.file_audit_log', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.file_audit_log'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT ON batch.file_error_record TO %I', :'export_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'export_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.file_error_record', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.file_error_record'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.file_record TO %I', :'export_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'export_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.file_record', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.file_record'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON batch.file_template_config TO %I', :'export_role') \gexec
    SELECT format('GRANT SELECT ON batch.job_instance TO %I', :'export_role') \gexec
    SELECT format('GRANT UPDATE (%I) ON batch.job_instance TO %I', COALESCE(
        (SELECT attname FROM pg_attribute WHERE attrelid = 'batch.job_instance'::regclass
            AND attname = 'updated_at' AND NOT attisdropped),
        (SELECT a.attname FROM pg_index i JOIN pg_attribute a
            ON a.attrelid = i.indrelid AND a.attnum = i.indkey[0]
          WHERE i.indrelid = 'batch.job_instance'::regclass AND i.indisprimary)), :'export_role') \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.pipeline_instance TO %I', :'export_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'export_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.pipeline_instance', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.pipeline_instance'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.pipeline_progress TO %I', :'export_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'export_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.pipeline_progress', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.pipeline_progress'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.pipeline_step_run TO %I', :'export_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'export_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.pipeline_step_run', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.pipeline_step_run'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.stateful_backend_binding TO %I', :'export_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'export_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.stateful_backend_binding', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.stateful_backend_binding'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT ON batch.stateful_backend_cutover_history TO %I', :'export_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'export_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.stateful_backend_cutover_history', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.stateful_backend_cutover_history'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT ON batch.step_registry TO %I', :'export_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'export_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.step_registry', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.step_registry'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.worker_report_outbox TO %I', :'export_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'export_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.worker_report_outbox', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.worker_report_outbox'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
  \endif
\endif

-- ===== process Worker =====
\if :{?process_role}
  SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'process_role' AND lower(rolname) <> 'public'
     AND NOT rolsuper AND NOT rolbypassrls) AS role_ok \gset
  \if :role_ok
  \else
    DO $$ BEGIN RAISE EXCEPTION 'process_role 不存在、为 PUBLIC，或具备 SUPERUSER/BYPASSRLS'; END $$;
  \endif
  SELECT format('REVOKE ALL ON ALL TABLES IN SCHEMA batch FROM %I', :'process_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'batch') \gexec
  SELECT format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA batch FROM %I', :'process_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'batch') \gexec
  SELECT format('REVOKE USAGE ON SCHEMA batch FROM %I', :'process_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'batch') \gexec
  SELECT format('REVOKE ALL ON ALL TABLES IN SCHEMA archive FROM %I', :'process_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'archive') \gexec
  SELECT format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA archive FROM %I', :'process_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'archive') \gexec
  SELECT format('REVOKE USAGE ON SCHEMA archive FROM %I', :'process_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'archive') \gexec
  \if :is_grant
    SELECT format('GRANT USAGE ON SCHEMA archive TO %I', :'process_role') \gexec
    SELECT format('GRANT USAGE ON SCHEMA batch TO %I', :'process_role') \gexec
    SELECT format('GRANT SELECT ON archive.job_task_archive TO %I', :'process_role') \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.biz_table_schema TO %I', :'process_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'process_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.biz_table_schema', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.biz_table_schema'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON batch.business_tenant_placement TO %I', :'process_role') \gexec
    SELECT format('GRANT INSERT, SELECT ON batch.file_audit_log TO %I', :'process_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'process_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.file_audit_log', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.file_audit_log'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT ON batch.file_error_record TO %I', :'process_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'process_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.file_error_record', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.file_error_record'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.file_record TO %I', :'process_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'process_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.file_record', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.file_record'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON batch.file_template_config TO %I', :'process_role') \gexec
    SELECT format('GRANT SELECT ON batch.job_instance TO %I', :'process_role') \gexec
    SELECT format('GRANT UPDATE (%I) ON batch.job_instance TO %I', COALESCE(
        (SELECT attname FROM pg_attribute WHERE attrelid = 'batch.job_instance'::regclass
            AND attname = 'updated_at' AND NOT attisdropped),
        (SELECT a.attname FROM pg_index i JOIN pg_attribute a
            ON a.attrelid = i.indrelid AND a.attnum = i.indkey[0]
          WHERE i.indrelid = 'batch.job_instance'::regclass AND i.indisprimary)), :'process_role') \gexec
    SELECT format('GRANT SELECT ON batch.job_partition TO %I', :'process_role') \gexec
    SELECT format('GRANT SELECT ON batch.job_task TO %I', :'process_role') \gexec
    SELECT format('GRANT UPDATE (process_staging_batch_key, process_staging_generation_id, updated_at) ON batch.job_task TO %I', :'process_role') \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.pipeline_instance TO %I', :'process_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'process_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.pipeline_instance', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.pipeline_instance'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.pipeline_progress TO %I', :'process_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'process_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.pipeline_progress', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.pipeline_progress'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.pipeline_step_run TO %I', :'process_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'process_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.pipeline_step_run', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.pipeline_step_run'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.shedlock TO %I', :'process_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'process_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.shedlock', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.shedlock'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.stateful_backend_binding TO %I', :'process_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'process_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.stateful_backend_binding', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.stateful_backend_binding'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT ON batch.stateful_backend_cutover_history TO %I', :'process_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'process_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.stateful_backend_cutover_history', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.stateful_backend_cutover_history'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT ON batch.step_registry TO %I', :'process_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'process_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.step_registry', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.step_registry'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON batch.tenant TO %I', :'process_role') \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.worker_report_outbox TO %I', :'process_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'process_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.worker_report_outbox', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.worker_report_outbox'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
  \endif
\endif

-- ===== dispatch Worker =====
\if :{?dispatch_role}
  SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'dispatch_role' AND lower(rolname) <> 'public'
     AND NOT rolsuper AND NOT rolbypassrls) AS role_ok \gset
  \if :role_ok
  \else
    DO $$ BEGIN RAISE EXCEPTION 'dispatch_role 不存在、为 PUBLIC，或具备 SUPERUSER/BYPASSRLS'; END $$;
  \endif
  SELECT format('REVOKE ALL ON ALL TABLES IN SCHEMA batch FROM %I', :'dispatch_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'batch') \gexec
  SELECT format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA batch FROM %I', :'dispatch_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'batch') \gexec
  SELECT format('REVOKE USAGE ON SCHEMA batch FROM %I', :'dispatch_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'batch') \gexec
  SELECT format('REVOKE ALL ON ALL TABLES IN SCHEMA archive FROM %I', :'dispatch_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'archive') \gexec
  SELECT format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA archive FROM %I', :'dispatch_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'archive') \gexec
  SELECT format('REVOKE USAGE ON SCHEMA archive FROM %I', :'dispatch_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'archive') \gexec
  \if :is_grant
    SELECT format('GRANT USAGE ON SCHEMA batch TO %I', :'dispatch_role') \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.biz_table_schema TO %I', :'dispatch_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'dispatch_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.biz_table_schema', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.biz_table_schema'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON batch.business_tenant_placement TO %I', :'dispatch_role') \gexec
    SELECT format('GRANT INSERT, SELECT ON batch.file_audit_log TO %I', :'dispatch_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'dispatch_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.file_audit_log', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.file_audit_log'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON batch.file_channel_config TO %I', :'dispatch_role') \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.file_channel_health TO %I', :'dispatch_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'dispatch_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.file_channel_health', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.file_channel_health'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.file_dispatch_record TO %I', :'dispatch_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'dispatch_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.file_dispatch_record', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.file_dispatch_record'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT ON batch.file_error_record TO %I', :'dispatch_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'dispatch_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.file_error_record', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.file_error_record'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.file_record TO %I', :'dispatch_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'dispatch_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.file_record', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.file_record'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON batch.file_template_config TO %I', :'dispatch_role') \gexec
    SELECT format('GRANT SELECT ON batch.job_instance TO %I', :'dispatch_role') \gexec
    SELECT format('GRANT UPDATE (%I) ON batch.job_instance TO %I', COALESCE(
        (SELECT attname FROM pg_attribute WHERE attrelid = 'batch.job_instance'::regclass
            AND attname = 'updated_at' AND NOT attisdropped),
        (SELECT a.attname FROM pg_index i JOIN pg_attribute a
            ON a.attrelid = i.indrelid AND a.attnum = i.indkey[0]
          WHERE i.indrelid = 'batch.job_instance'::regclass AND i.indisprimary)), :'dispatch_role') \gexec
    SELECT format('GRANT SELECT ON batch.job_task TO %I', :'dispatch_role') \gexec
    SELECT format('GRANT UPDATE (external_outcome_resolution_consumed_at, updated_at) ON batch.job_task TO %I', :'dispatch_role') \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.pipeline_instance TO %I', :'dispatch_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'dispatch_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.pipeline_instance', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.pipeline_instance'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.pipeline_progress TO %I', :'dispatch_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'dispatch_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.pipeline_progress', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.pipeline_progress'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.pipeline_step_run TO %I', :'dispatch_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'dispatch_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.pipeline_step_run', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.pipeline_step_run'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON batch.secret_version TO %I', :'dispatch_role') \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.shedlock TO %I', :'dispatch_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'dispatch_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.shedlock', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.shedlock'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.stateful_backend_binding TO %I', :'dispatch_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'dispatch_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.stateful_backend_binding', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.stateful_backend_binding'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT ON batch.stateful_backend_cutover_history TO %I', :'dispatch_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'dispatch_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.stateful_backend_cutover_history', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.stateful_backend_cutover_history'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT ON batch.step_registry TO %I', :'dispatch_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'dispatch_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.step_registry', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.step_registry'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON batch.trigger_request TO %I', :'dispatch_role') \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.worker_report_outbox TO %I', :'dispatch_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'dispatch_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.worker_report_outbox', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.worker_report_outbox'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON batch.workflow_run TO %I', :'dispatch_role') \gexec
  \endif
\endif

-- ===== atomic Worker =====
\if :{?atomic_role}
  SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'atomic_role' AND lower(rolname) <> 'public'
     AND NOT rolsuper AND NOT rolbypassrls) AS role_ok \gset
  \if :role_ok
  \else
    DO $$ BEGIN RAISE EXCEPTION 'atomic_role 不存在、为 PUBLIC，或具备 SUPERUSER/BYPASSRLS'; END $$;
  \endif
  SELECT format('REVOKE ALL ON ALL TABLES IN SCHEMA batch FROM %I', :'atomic_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'batch') \gexec
  SELECT format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA batch FROM %I', :'atomic_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'batch') \gexec
  SELECT format('REVOKE USAGE ON SCHEMA batch FROM %I', :'atomic_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'batch') \gexec
  SELECT format('REVOKE ALL ON ALL TABLES IN SCHEMA archive FROM %I', :'atomic_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'archive') \gexec
  SELECT format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA archive FROM %I', :'atomic_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'archive') \gexec
  SELECT format('REVOKE USAGE ON SCHEMA archive FROM %I', :'atomic_role') WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'archive') \gexec
  \if :is_grant
    SELECT format('GRANT USAGE ON SCHEMA batch TO %I', :'atomic_role') \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.biz_table_schema TO %I', :'atomic_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'atomic_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.biz_table_schema', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.biz_table_schema'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON batch.business_tenant_placement TO %I', :'atomic_role') \gexec
    SELECT format('GRANT INSERT, SELECT ON batch.file_audit_log TO %I', :'atomic_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'atomic_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.file_audit_log', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.file_audit_log'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT ON batch.file_error_record TO %I', :'atomic_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'atomic_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.file_error_record', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.file_error_record'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.file_record TO %I', :'atomic_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'atomic_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.file_record', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.file_record'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT SELECT ON batch.file_template_config TO %I', :'atomic_role') \gexec
    SELECT format('GRANT SELECT ON batch.job_instance TO %I', :'atomic_role') \gexec
    SELECT format('GRANT UPDATE (%I) ON batch.job_instance TO %I', COALESCE(
        (SELECT attname FROM pg_attribute WHERE attrelid = 'batch.job_instance'::regclass
            AND attname = 'updated_at' AND NOT attisdropped),
        (SELECT a.attname FROM pg_index i JOIN pg_attribute a
            ON a.attrelid = i.indrelid AND a.attnum = i.indkey[0]
          WHERE i.indrelid = 'batch.job_instance'::regclass AND i.indisprimary)), :'atomic_role') \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.pipeline_instance TO %I', :'atomic_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'atomic_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.pipeline_instance', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.pipeline_instance'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.pipeline_progress TO %I', :'atomic_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'atomic_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.pipeline_progress', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.pipeline_progress'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.pipeline_step_run TO %I', :'atomic_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'atomic_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.pipeline_step_run', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.pipeline_step_run'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT, UPDATE ON batch.stateful_backend_binding TO %I', :'atomic_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'atomic_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.stateful_backend_binding', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.stateful_backend_binding'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT INSERT, SELECT ON batch.stateful_backend_cutover_history TO %I', :'atomic_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'atomic_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.stateful_backend_cutover_history', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.stateful_backend_cutover_history'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT ON batch.step_registry TO %I', :'atomic_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'atomic_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.step_registry', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.step_registry'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
    SELECT format('GRANT DELETE, INSERT, SELECT, UPDATE ON batch.worker_report_outbox TO %I', :'atomic_role') \gexec
    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'atomic_role')
      FROM (SELECT DISTINCT pg_get_serial_sequence('batch.worker_report_outbox', attname) AS sequence_name
              FROM pg_attribute
             WHERE attrelid = 'batch.worker_report_outbox'::regclass AND attnum > 0 AND NOT attisdropped) sequences
     WHERE sequence_name IS NOT NULL \gexec
  \endif
\endif

COMMIT;
