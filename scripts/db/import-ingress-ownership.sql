-- 用途：由平台库管理员预先声明、维护和受控切换入站扫描范围。
-- 用法：psql -X -v ON_ERROR_STOP=1 -v scope_id=范围摘要 -f scripts/db/import-ingress-ownership.sql
-- 默认为 preview；具体动作和参数见 scripts/db/README.md。执行前须停止受影响的 scanner 和对象对账。

\set ON_ERROR_STOP on
\if :{?scope_id}
\else
  DO $$ BEGIN RAISE EXCEPTION '必须指定 scope_id'; END $$;
\endif
\if :{?action}
\else
  \set action preview
\endif

BEGIN;
SELECT :'action' = 'declare' AS is_declare,
       :'action' = 'manifest' AS is_manifest,
       :'action' = 'channel' AS is_channel,
       :'action' = 'disable-binding' AS is_disable_binding,
       :'action' = 'retire' AS is_retire,
       :'action' = 'activate' AS is_activate,
       :'action' = 'release' AS is_release,
       :'action' = 'preview' AS is_preview \gset

\if :is_declare
  \if :{?storage_id}
  \else
    DO $$ BEGIN RAISE EXCEPTION 'declare 必须指定 storage_id'; END $$;
  \endif
  \if :{?bucket_name}
  \else
    DO $$ BEGIN RAISE EXCEPTION 'declare 必须指定 bucket_name'; END $$;
  \endif
  \if :{?object_prefix}
  \else
    DO $$ BEGIN RAISE EXCEPTION 'declare 必须指定 object_prefix，允许显式空前缀'; END $$;
  \endif
  INSERT INTO batch.import_ingress_ownership(scope_id, storage_id, bucket_name, object_prefix, state)
  VALUES (:'scope_id', :'storage_id', :'bucket_name', :'object_prefix', 'RETIRED')
  ON CONFLICT (scope_id) DO NOTHING;
  SELECT EXISTS (
      SELECT 1 FROM batch.import_ingress_ownership
       WHERE scope_id = :'scope_id' AND storage_id = :'storage_id'
         AND bucket_name = :'bucket_name' AND object_prefix = :'object_prefix'
  ) AS same_scope \gset
  \if :same_scope
  \else
    DO $$ BEGIN RAISE EXCEPTION '已存在的范围身份与声明内容不一致'; END $$;
  \endif
\elif :is_preview
  -- 预览不改变声明。
\else
  SELECT EXISTS (
      SELECT 1 FROM batch.import_ingress_ownership
       WHERE scope_id = :'scope_id'
  ) AS scope_exists \gset
  \if :scope_exists
  \else
    DO $$ BEGIN RAISE EXCEPTION '归属范围不存在，请先声明'; END $$;
  \endif
  SELECT EXISTS (
      SELECT 1 FROM batch.import_ingress_ownership
       WHERE scope_id = :'scope_id' AND state = 'RETIRED'
  ) AS scope_retired \gset

  \if :is_manifest
    \if :scope_retired
    \else
      DO $$ BEGIN RAISE EXCEPTION '修改绑定前须先退休范围并停止相关扫描和对账'; END $$;
    \endif
    \if :{?tenant_id}
    \else
      DO $$ BEGIN RAISE EXCEPTION 'manifest 必须指定 tenant_id'; END $$;
    \endif
    \if :{?binding_prefix}
    \else
      DO $$ BEGIN RAISE EXCEPTION 'manifest 必须指定 binding_prefix'; END $$;
    \endif
    \if :{?file_group_code}
    \else
      DO $$ BEGIN RAISE EXCEPTION 'manifest 必须指定 file_group_code'; END $$;
    \endif
    \if :{?manifest_key_template}
    \else
      DO $$ BEGIN RAISE EXCEPTION 'manifest 必须指定 manifest_key_template'; END $$;
    \endif
    SELECT :'file_group_code' ~ '^[a-zA-Z][a-zA-Z0-9_-]{0,127}$'
       AND left(:'binding_prefix', length(object_prefix)) = object_prefix
       AND left(:'manifest_key_template', 1) <> '/'
       AND position('/../' IN '/' || :'manifest_key_template' || '/') = 0
       AND (length(:'manifest_key_template') - length(replace(replace(:'manifest_key_template',
            '{bizDate}', ''), '{bizDateCompact}', ''))) IN (9, 16)
       AND position('{' IN replace(replace(:'manifest_key_template', '{bizDate}', ''),
            '{bizDateCompact}', '')) = 0
       AND position('}' IN replace(replace(:'manifest_key_template', '{bizDate}', ''),
            '{bizDateCompact}', '')) = 0 AS valid_binding
      FROM batch.import_ingress_ownership WHERE scope_id = :'scope_id' \gset
    \if :valid_binding
    \else
      DO $$ BEGIN RAISE EXCEPTION '清单绑定目录或日期模板无效'; END $$;
    \endif
    UPDATE batch.import_ingress_ownership_binding SET enabled = false
     WHERE scope_id = :'scope_id' AND binding_type = 'MANIFEST'
       AND tenant_id = :'tenant_id' AND object_prefix = :'binding_prefix' AND enabled
       AND (file_group_code IS DISTINCT FROM :'file_group_code'
            OR manifest_key_template IS DISTINCT FROM :'manifest_key_template');
    UPDATE batch.import_ingress_ownership_binding SET enabled = true
     WHERE id = (
       SELECT id FROM batch.import_ingress_ownership_binding
        WHERE scope_id = :'scope_id' AND binding_type = 'MANIFEST'
          AND tenant_id = :'tenant_id' AND object_prefix = :'binding_prefix'
          AND file_group_code = :'file_group_code' AND manifest_key_template = :'manifest_key_template'
          AND NOT enabled ORDER BY id DESC LIMIT 1
     ) AND NOT EXISTS (
       SELECT 1 FROM batch.import_ingress_ownership_binding
        WHERE scope_id = :'scope_id' AND binding_type = 'MANIFEST'
          AND tenant_id = :'tenant_id' AND object_prefix = :'binding_prefix' AND enabled
     );
    INSERT INTO batch.import_ingress_ownership_binding
        (scope_id, binding_type, tenant_id, object_prefix, file_group_code, manifest_key_template)
    SELECT :'scope_id', 'MANIFEST', :'tenant_id', :'binding_prefix', :'file_group_code', :'manifest_key_template'
     WHERE NOT EXISTS (
       SELECT 1 FROM batch.import_ingress_ownership_binding
        WHERE scope_id = :'scope_id' AND binding_type = 'MANIFEST'
          AND tenant_id = :'tenant_id' AND object_prefix = :'binding_prefix' AND enabled
     );
    UPDATE batch.import_ingress_ownership SET updated_by = current_user, updated_at = clock_timestamp(),
           change_reason = '更新清单绑定' WHERE scope_id = :'scope_id';
  \elif :is_channel
    \if :scope_retired
    \else
      DO $$ BEGIN RAISE EXCEPTION '修改绑定前须先退休范围并停止相关扫描和对账'; END $$;
    \endif
    \if :{?tenant_id}
    \else
      DO $$ BEGIN RAISE EXCEPTION 'channel 必须指定 tenant_id'; END $$;
    \endif
    \if :{?binding_prefix}
    \else
      DO $$ BEGIN RAISE EXCEPTION 'channel 必须指定 binding_prefix'; END $$;
    \endif
    \if :{?channel_code}
    \else
      DO $$ BEGIN RAISE EXCEPTION 'channel 必须指定 channel_code'; END $$;
    \endif
    SELECT :'channel_code' ~ '^[a-zA-Z][a-zA-Z0-9_-]{0,127}$'
       AND left(:'binding_prefix', length(object_prefix)) = object_prefix AS valid_binding
      FROM batch.import_ingress_ownership WHERE scope_id = :'scope_id' \gset
    \if :valid_binding
    \else
      DO $$ BEGIN RAISE EXCEPTION '渠道绑定目录或编码无效'; END $$;
    \endif
    UPDATE batch.import_ingress_ownership_binding SET enabled = false
     WHERE scope_id = :'scope_id' AND binding_type = 'CHANNEL'
       AND tenant_id = :'tenant_id' AND object_prefix = :'binding_prefix' AND enabled
       AND channel_code IS DISTINCT FROM :'channel_code';
    UPDATE batch.import_ingress_ownership_binding SET enabled = true
     WHERE id = (
       SELECT id FROM batch.import_ingress_ownership_binding
        WHERE scope_id = :'scope_id' AND binding_type = 'CHANNEL'
          AND tenant_id = :'tenant_id' AND object_prefix = :'binding_prefix'
          AND channel_code = :'channel_code' AND NOT enabled ORDER BY id DESC LIMIT 1
     ) AND NOT EXISTS (
       SELECT 1 FROM batch.import_ingress_ownership_binding
        WHERE scope_id = :'scope_id' AND binding_type = 'CHANNEL'
          AND tenant_id = :'tenant_id' AND object_prefix = :'binding_prefix' AND enabled
     );
    INSERT INTO batch.import_ingress_ownership_binding
        (scope_id, binding_type, tenant_id, object_prefix, channel_code)
    SELECT :'scope_id', 'CHANNEL', :'tenant_id', :'binding_prefix', :'channel_code'
     WHERE NOT EXISTS (
       SELECT 1 FROM batch.import_ingress_ownership_binding
        WHERE scope_id = :'scope_id' AND binding_type = 'CHANNEL'
          AND tenant_id = :'tenant_id' AND object_prefix = :'binding_prefix' AND enabled
     );
    UPDATE batch.import_ingress_ownership SET updated_by = current_user, updated_at = clock_timestamp(),
           change_reason = '更新渠道绑定' WHERE scope_id = :'scope_id';
  \elif :is_disable_binding
    \if :scope_retired
    \else
      DO $$ BEGIN RAISE EXCEPTION '停用绑定前须先退休范围并停止相关扫描和对账'; END $$;
    \endif
    \if :{?binding_type}
    \else
      DO $$ BEGIN RAISE EXCEPTION 'disable-binding 必须指定 binding_type'; END $$;
    \endif
    \if :{?tenant_id}
    \else
      DO $$ BEGIN RAISE EXCEPTION 'disable-binding 必须指定 tenant_id'; END $$;
    \endif
    \if :{?binding_prefix}
    \else
      DO $$ BEGIN RAISE EXCEPTION 'disable-binding 必须指定 binding_prefix'; END $$;
    \endif
    UPDATE batch.import_ingress_ownership_binding SET enabled = false
     WHERE scope_id = :'scope_id' AND binding_type = :'binding_type' AND tenant_id = :'tenant_id'
       AND object_prefix = :'binding_prefix' AND enabled;
    UPDATE batch.import_ingress_ownership SET updated_by = current_user, updated_at = clock_timestamp(),
           change_reason = '停用绑定' WHERE scope_id = :'scope_id';
  \elif :is_retire
    UPDATE batch.import_ingress_ownership
       SET state = 'RETIRED', updated_by = current_user, updated_at = clock_timestamp(), change_reason = NULL
     WHERE scope_id = :'scope_id' AND state IN ('ACTIVE', 'RELEASED');
  \elif :is_activate
    \if :scope_retired
    \else
      DO $$ BEGIN RAISE EXCEPTION '只能激活 RETIRED 范围；已释放范围须先重新退休以恢复保护'; END $$;
    \endif
    UPDATE batch.import_ingress_ownership
       SET state = 'ACTIVE', updated_by = current_user, updated_at = clock_timestamp(), change_reason = NULL
     WHERE scope_id = :'scope_id';
  \elif :is_release
    \if :scope_retired
    \else
      DO $$ BEGIN RAISE EXCEPTION '只能释放 RETIRED 范围'; END $$;
    \endif
    \if :{?reason}
    \else
      DO $$ BEGIN RAISE EXCEPTION '释放范围必须指定 reason'; END $$;
    \endif
    SELECT length(trim(:'reason')) > 0 AS reason_present \gset
    \if :reason_present
    \else
      DO $$ BEGIN RAISE EXCEPTION '释放原因不能为空'; END $$;
    \endif
    SELECT NOT EXISTS (
        SELECT 1 FROM batch.import_ingress_scan_state
         WHERE scope_id = :'scope_id' AND lease_owner IS NOT NULL AND lease_until > clock_timestamp()
    ) AS no_active_lease \gset
    \if :no_active_lease
    \else
      DO $$ BEGIN RAISE EXCEPTION '范围仍有有效扫描租约，不能释放'; END $$;
    \endif
    UPDATE batch.import_ingress_ownership
       SET state = 'RELEASED', updated_by = current_user, updated_at = clock_timestamp(), change_reason = :'reason'
     WHERE scope_id = :'scope_id';
  \else
    DO $$ BEGIN RAISE EXCEPTION '未知 action'; END $$;
  \endif
\endif

SELECT scope_id, storage_id, bucket_name, object_prefix, state, updated_by, change_reason, updated_at
  FROM batch.import_ingress_ownership WHERE scope_id = :'scope_id';
SELECT binding_type, tenant_id, object_prefix, file_group_code, manifest_key_template, channel_code, enabled
  FROM batch.import_ingress_ownership_binding
 WHERE scope_id = :'scope_id' ORDER BY binding_type, tenant_id, object_prefix, id;
COMMIT;
