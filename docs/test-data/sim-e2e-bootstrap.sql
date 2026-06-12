-- ============================================================================
-- sim-e2e-bootstrap.sql
--
-- A4 fixture 工程化产物:把 sim-e2e (2026-05-29) 第 1+2 波 P0-P3 验证收尾
-- 累计的手术 SQL 收成幂等一键脚本。导入 ta/tb/tc fixture xlsx 后,运行一次
-- 即可让 ta/tb/tc 业务跑通 reconciler / scheduler / state-machine 路径。
--
-- 用法:
--   docker exec -i batch-postgres-primary \
--     psql -U batch -d batch -v ON_ERROR_STOP=1 \
--     -f /dev/stdin < docs/test-data/sim-e2e-bootstrap.sql
--
-- 幂等性:所有 INSERT 走 ON CONFLICT DO UPDATE;所有 UPDATE 用 WHERE 收敛到
-- 仅 ta/tb/tc 三租户。允许重复执行,二次运行不报错、不重复改。
--
-- 涵盖的修正(对齐 docs/verifications/sim-e2e-2026-05-29.md §7 + §11):
--   1.  batch.tenant 补 ta/tb/tc ACTIVE 行(reconciler/scheduler 依赖)
--   2.  job_definition.execution_mode = 'FULL' 全置位
--   3.  IMPORT pipeline 补齐 5-step:RECEIVE/PREPROCESS/PARSE/VALIDATE/LOAD
--       (修复 file_state RECEIVED -> PARSED 状态机禁止跳过 PREPROCESS)
--   4.  EXPORT pipeline 补齐 5-step:PREPARE/GENERATE/STORE/REGISTER/COMPLETE
--       (修复 tb 只有 GENERATE 一步导致 OutboundFile 卡 GENERATED 状态)
--   5.  pipeline_step_definition.impl_code 命名约定校准
--       (IMPORT/EXPORT/DISPATCH 保持 SCREAMING_SNAKE,仅 PROCESS 用 camelCase sqlTransformCompute)
--   6.  file_channel_config.config_json SFTP key 命名统一 sftp_*
--   7.  file_template_config:
--         IMPORT TPL  -> query_param_schema 注入 jdbcMappedImport spec
--         EXPORT TPL  -> default_query_sql 带 :tenantId AND :batchNo
--                     -> query_param_schema 注入 sqlTemplateExport.cursorColumn
--   8.  pipeline_step_definition.step_params 补 jdbcMappedImport /
--       sqlTemplateExport spec 模板(为空 {} 才补,已设置的不动)
-- ============================================================================

\set ON_ERROR_STOP on
BEGIN;

-- ----------------------------------------------------------------------------
-- 0. tenant_id 集合(只对这三个租户作用)
-- ----------------------------------------------------------------------------
-- 注意:这里直接展开是为了让所有后续语句独立可读、可单独 copy 调试,而不是
-- 临时表;所有 WHERE 子句都显式列租户白名单。

-- ----------------------------------------------------------------------------
-- 1. batch.tenant 上有 ta / tb / tc 三行 ACTIVE,reconciler & quartz 才挑得到
-- ----------------------------------------------------------------------------
INSERT INTO batch.tenant (tenant_id, tenant_name, status, description, created_by, updated_at)
VALUES
    ('ta', 'ta', 'ACTIVE', 'sim-e2e bootstrap (A4 fixture engineering)', 'sim-e2e', now()),
    ('tb', 'tb', 'ACTIVE', 'sim-e2e bootstrap (A4 fixture engineering)', 'sim-e2e', now()),
    ('tc', 'tc', 'ACTIVE', 'sim-e2e bootstrap (A4 fixture engineering)', 'sim-e2e', now())
ON CONFLICT (tenant_id) DO UPDATE
SET status      = 'ACTIVE',
    description = COALESCE(batch.tenant.description, EXCLUDED.description),
    -- Citus:batch.tenant 是 distributed,DO UPDATE SET 函数须 IMMUTABLE;CURRENT_TIMESTAMP 改 EXCLUDED 引用(双栈等价)
    updated_at  = EXCLUDED.updated_at;

-- ----------------------------------------------------------------------------
-- 2. job_definition.execution_mode = 'FULL'(V73 默认应是 FULL,但旧数据可能空)
-- ----------------------------------------------------------------------------
UPDATE batch.job_definition
SET execution_mode = 'FULL',
    updated_at     = CURRENT_TIMESTAMP
WHERE tenant_id IN ('ta','tb','tc')
  AND (execution_mode IS NULL OR execution_mode <> 'FULL');

-- ----------------------------------------------------------------------------
-- 3. pipeline_step_definition.impl_code 命名约定
--     **IMPORT/EXPORT/DISPATCH 用 SCREAMING_SNAKE**(代码 default `stepCode()` =
--     `IMPORT_RECEIVE` 等,见 ImportStageStep:19);**PROCESS 用 camelCase**
--     (SqlTransformComputePlugin.PLUGIN_ID = "sqlTransformCompute")。
--     仅 PROCESS 类需要 SCREAMING_SNAKE → camelCase 映射,其他 step 保持原样。
--     (A2 agent 发现:整批 camelCase 改名会撞 DefaultImportStageExecutor
--      stepsByImplCode lookup,导致 STEP_NOT_FOUND)
--     legacy(若 fixture 不慎写错)         正确值
--     PROCESS_SQL_TRANSFORM_COMPUTE     sqlTransformCompute
--     IMPORT_PREPROCESS               importPreprocess
--     IMPORT_PARSE                    importParse
--     IMPORT_VALIDATE                 importValidate
--     IMPORT_LOAD                     importLoad
--     IMPORT_FEEDBACK                 importFeedback
--     EXPORT_PREPARE                  exportPrepare
--     EXPORT_GENERATE                 exportGenerate
--     EXPORT_STORE                    exportStore
--     EXPORT_REGISTER                 exportRegister
--     EXPORT_COMPLETE                 exportComplete
--     DISPATCH_PREPARE                dispatchPrepare
--     DISPATCH_DISPATCH               dispatchDispatch
--     DISPATCH_ACK                    dispatchAck
--     DISPATCH_RETRY                  dispatchRetry
--     DISPATCH_COMPENSATE             dispatchCompensate
--     DISPATCH_COMPLETE               dispatchComplete
--     PROCESS_SQL_TRANSFORM_COMPUTE   sqlTransformCompute   (任务说明示例)
-- ----------------------------------------------------------------------------
UPDATE batch.pipeline_step_definition AS psd
SET impl_code  = m.canonical,
    updated_at = CURRENT_TIMESTAMP
FROM (
    VALUES
        -- 仅 PROCESS plugin 必须 camelCase
        ('PROCESS_SQL_TRANSFORM_COMPUTE','sqlTransformCompute')
        -- IMPORT/EXPORT/DISPATCH 保持 SCREAMING_SNAKE,不在此处改名
) AS m(legacy, canonical),
     batch.pipeline_definition pd
WHERE pd.id = psd.pipeline_definition_id
  AND pd.tenant_id IN ('ta','tb','tc')
  AND psd.impl_code = m.legacy;

-- ----------------------------------------------------------------------------
-- 4. IMPORT pipeline 补齐 5-step:RECEIVE / PREPROCESS / PARSE / VALIDATE / LOAD
--     (FEEDBACK 不强补,worker 端不在最小可跑路径)
-- ----------------------------------------------------------------------------
WITH targets AS (
    SELECT pd.id              AS pd_id,
           pd.tenant_id,
           pd.job_code
    FROM batch.pipeline_definition pd
    WHERE pd.tenant_id IN ('ta','tb','tc')
      AND pd.pipeline_type = 'IMPORT'
), missing AS (
    SELECT t.pd_id, t.tenant_id, t.job_code, s.stage_code, s.step_order, s.step_code,
           s.step_name, s.impl_code
    FROM targets t
    CROSS JOIN (VALUES
        ('RECEIVE',    1, 'STEP_RECEIVE',    '接收文件',  'IMPORT_RECEIVE'),
        ('PREPROCESS', 2, 'STEP_PREPROCESS', '预处理',    'IMPORT_PREPROCESS'),
        ('PARSE',      3, 'STEP_PARSE',      '解析',      'IMPORT_PARSE'),
        ('VALIDATE',   4, 'STEP_VALIDATE',   '校验',      'IMPORT_VALIDATE'),
        ('LOAD',       5, 'STEP_LOAD',       '入库',      'IMPORT_LOAD')
    ) AS s(stage_code, step_order, step_code, step_name, impl_code)
    WHERE NOT EXISTS (
        SELECT 1 FROM batch.pipeline_step_definition x
        WHERE x.pipeline_definition_id = t.pd_id
          AND x.stage_code = s.stage_code
    )
)
INSERT INTO batch.pipeline_step_definition (
    pipeline_definition_id, step_code, step_name, stage_code, step_order,
    impl_code, step_params, timeout_seconds, retry_policy, retry_max_count, enabled
)
SELECT pd_id, step_code, step_name, stage_code, step_order, impl_code,
       '{}'::jsonb, 120, 'NONE', 0, TRUE
FROM missing
ON CONFLICT (pipeline_definition_id, step_code) DO NOTHING;

-- ----------------------------------------------------------------------------
-- 5. EXPORT pipeline 补齐 5-step:PREPARE/GENERATE/STORE/REGISTER/COMPLETE
--     (tb 原只有 GENERATE 一步)
-- ----------------------------------------------------------------------------
WITH targets AS (
    SELECT pd.id AS pd_id, pd.tenant_id, pd.job_code
    FROM batch.pipeline_definition pd
    WHERE pd.tenant_id IN ('ta','tb','tc')
      AND pd.pipeline_type = 'EXPORT'
), missing AS (
    SELECT t.pd_id, s.stage_code, s.step_order, s.step_code, s.step_name, s.impl_code
    FROM targets t
    CROSS JOIN (VALUES
        ('PREPARE',  1, 'STEP_PREPARE',  '导出准备', 'EXPORT_PREPARE'),
        ('GENERATE', 2, 'STEP_GENERATE', '生成文件', 'EXPORT_GENERATE'),
        ('STORE',    3, 'STEP_STORE',    '落地存储', 'EXPORT_STORE'),
        ('REGISTER', 4, 'STEP_REGISTER', '登记产物', 'EXPORT_REGISTER'),
        ('COMPLETE', 5, 'STEP_COMPLETE', '完成回执', 'EXPORT_COMPLETE')
    ) AS s(stage_code, step_order, step_code, step_name, impl_code)
    WHERE NOT EXISTS (
        SELECT 1 FROM batch.pipeline_step_definition x
        WHERE x.pipeline_definition_id = t.pd_id
          AND x.stage_code = s.stage_code
    )
)
INSERT INTO batch.pipeline_step_definition (
    pipeline_definition_id, step_code, step_name, stage_code, step_order,
    impl_code, step_params, timeout_seconds, retry_policy, retry_max_count, enabled
)
SELECT pd_id, step_code, step_name, stage_code, step_order, impl_code,
       '{}'::jsonb, 300, 'NONE', 0, TRUE
FROM missing
ON CONFLICT (pipeline_definition_id, step_code) DO NOTHING;

-- ----------------------------------------------------------------------------
-- 6. file_channel_config.config_json SFTP key 统一 sftp_* 命名(SQL 兜底)
--     仅作用于 channel_type='SFTP' 且仍残留旧 key 的行。
-- ----------------------------------------------------------------------------
-- Citus:本 SFTP key 归一化是 distributed 表上带相关 jsonb 子查询(jsonb_object_agg+jsonb_each(config_json))
-- 的 UPDATE,下推到分片 deparse 失败(text = boolean)。它纯属把 host/sftpHost 等旧 key 统一成 sftp_*,
-- console tenant-package 导入已给正确 key,这步多为空操作。包进 DO 块,失败即跳过——普通 PG 正常归一,
-- Citus 跳过不阻断后续 bootstrap(SFTP 键名只影响 dispatch 阶段)。双栈安全。
DO $sftp_norm$
BEGIN
UPDATE batch.file_channel_config
SET config_json = (
        SELECT jsonb_object_agg(
                   CASE k
                       WHEN 'host'          THEN 'sftp_host'
                       WHEN 'sftpHost'      THEN 'sftp_host'
                       WHEN 'port'          THEN 'sftp_port'
                       WHEN 'sftpPort'      THEN 'sftp_port'
                       WHEN 'username'      THEN 'sftp_username'
                       WHEN 'sftpUsername'  THEN 'sftp_username'
                       WHEN 'password'      THEN 'sftp_password'
                       WHEN 'sftpPassword'  THEN 'sftp_password'
                       WHEN 'remotePath'    THEN 'sftp_remote_path'
                       WHEN 'sftpRemotePath' THEN 'sftp_remote_path'
                       WHEN 'remote_path'   THEN 'sftp_remote_path'
                       ELSE k
                   END,
                   v
               )
        FROM jsonb_each(config_json) AS j(k, v)
    ),
    updated_at = CURRENT_TIMESTAMP
WHERE tenant_id IN ('ta','tb','tc')
  AND channel_type = 'SFTP'
  AND (jsonb_exists(config_json, 'host') OR jsonb_exists(config_json, 'port') OR jsonb_exists(config_json, 'username')
       OR jsonb_exists(config_json, 'password') OR jsonb_exists(config_json, 'remotePath')
       OR jsonb_exists(config_json, 'sftpHost') OR jsonb_exists(config_json, 'sftpPort')
       OR jsonb_exists(config_json, 'sftpUsername') OR jsonb_exists(config_json, 'sftpPassword')
       OR jsonb_exists(config_json, 'sftpRemotePath') OR jsonb_exists(config_json, 'remote_path'));
EXCEPTION WHEN OTHERS THEN
  RAISE NOTICE 'SFTP channel key 归一化在 Citus 上跳过(分布式 UPDATE 相关 jsonb 子查询不支持): %', SQLERRM;
END
$sftp_norm$;

-- ----------------------------------------------------------------------------
-- 7a. IMPORT file_template_config.query_param_schema 注入 jdbcMappedImport spec
--      (只在 schema 为空 {} 时设;已有自定义不动)
-- ----------------------------------------------------------------------------
UPDATE batch.file_template_config
SET query_param_schema = jsonb_build_object(
        'spec', 'jdbcMappedImport',
        'version', '1',
        'params', jsonb_build_object(
            'tenantId',  jsonb_build_object('type','string','required', true),
            'batchNo',   jsonb_build_object('type','string','required', true),
            'batchDate', jsonb_build_object('type','date','required', true)
        )
    ),
    updated_at = CURRENT_TIMESTAMP
WHERE tenant_id IN ('ta','tb','tc')
  AND template_type = 'IMPORT'
  AND (query_param_schema IS NULL OR query_param_schema = '{}'::jsonb);

-- ----------------------------------------------------------------------------
-- 7b. EXPORT file_template_config.default_query_sql + query_param_schema
--      默认 SQL 必须带 :tenantId AND :batchNo IS NOT NULL,防止 export 拉全表;
--      query_param_schema 注入 sqlTemplateExport.cursorColumn。
-- ----------------------------------------------------------------------------
UPDATE batch.file_template_config
SET default_query_sql = COALESCE(NULLIF(default_query_sql, ''),
        '')
WHERE FALSE; -- no-op,占位让 diff 更清晰

UPDATE batch.file_template_config
SET default_query_sql = 'SELECT * FROM ' || lower(biz_type) || '_export '
                         || 'WHERE tenant_id = :tenantId '
                         || 'AND batch_no = :batchNo '
                         || 'AND :batchNo IS NOT NULL '
                         || 'ORDER BY id',
    updated_at        = CURRENT_TIMESTAMP
WHERE tenant_id IN ('ta','tb','tc')
  AND template_type = 'EXPORT'
  AND (default_query_sql IS NULL
       OR default_query_sql = ''
       OR default_query_sql NOT ILIKE '%:tenantId%'
       OR default_query_sql NOT ILIKE '%:batchNo IS NOT NULL%');

UPDATE batch.file_template_config
SET query_param_schema = jsonb_build_object(
        'spec', 'sqlTemplateExport',
        'version', '1',
        'cursorColumn', 'id',
        'params', jsonb_build_object(
            'tenantId', jsonb_build_object('type','string','required', true),
            'batchNo',  jsonb_build_object('type','string','required', true)
        )
    ),
    updated_at = CURRENT_TIMESTAMP
WHERE tenant_id IN ('ta','tb','tc')
  AND template_type = 'EXPORT'
  AND (query_param_schema IS NULL OR query_param_schema = '{}'::jsonb);

-- ----------------------------------------------------------------------------
-- 8. pipeline_step_definition.step_params 补 spec 模板(仅 {} 才补)
--     IMPORT_*  -> jdbcMappedImport
--     EXPORT_*  -> sqlTemplateExport
-- ----------------------------------------------------------------------------
UPDATE batch.pipeline_step_definition AS psd
SET step_params = jsonb_build_object(
        'spec', 'jdbcMappedImport',
        'version', '1',
        'commitBatchSize', 500
    ),
    updated_at  = CURRENT_TIMESTAMP
FROM batch.pipeline_definition pd
WHERE psd.pipeline_definition_id = pd.id
  AND pd.tenant_id IN ('ta','tb','tc')
  AND pd.pipeline_type = 'IMPORT'
  AND psd.stage_code IN ('PARSE','VALIDATE','LOAD')
  AND (psd.step_params IS NULL OR psd.step_params = '{}'::jsonb);

UPDATE batch.pipeline_step_definition AS psd
SET step_params = jsonb_build_object(
        'spec', 'sqlTemplateExport',
        'version', '1',
        'cursorColumn', 'id',
        'fetchSize', 1000
    ),
    updated_at  = CURRENT_TIMESTAMP
FROM batch.pipeline_definition pd
WHERE psd.pipeline_definition_id = pd.id
  AND pd.tenant_id IN ('ta','tb','tc')
  AND pd.pipeline_type = 'EXPORT'
  AND psd.stage_code IN ('PREPARE','GENERATE')
  AND (psd.step_params IS NULL OR psd.step_params = '{}'::jsonb);

-- ----------------------------------------------------------------------------
-- 9. 2026-06-08 runtime config refresh:让 11-sheet Excel 包可被当前 worker 真正执行。
--    早期 fixture 只放占位 spec / demo SQL；当前 worker 需要：
--      * IMPORT: query_param_schema.jdbcMappedImport + load_target_ref='jdbc_mapped'
--      * EXPORT: export_data_ref='sql_template_export' + SELECT 列含 id + :batchNo 引用
--      * DISPATCH: 本地 worker 是宿主机 JVM,HTTP mock 必须走 localhost:11080
-- ----------------------------------------------------------------------------
UPDATE batch.file_template_config
SET query_param_schema = '{
      "jdbcMappedImport": {
        "schema": "biz",
        "table": "customer_account",
        "tenantColumn": "tenant_id",
        "columnMappings": [
          {"from": "customer_no", "to": "customer_no"},
          {"from": "customer_name", "to": "customer_name"},
          {"from": "customer_type", "to": "customer_type"},
          {"from": "certificate_no", "to": "certificate_no"},
          {"from": "mobile_no", "to": "mobile_no"},
          {"from": "email", "to": "email"},
          {"from": "status", "to": "status"}
        ],
        "conflictColumns": ["tenant_id", "customer_no"],
        "systemBindings": {
          "source_file_name": "${sourceFileName}",
          "source_batch_no": "${batchNo}",
          "source_trace_id": "${traceId}",
          "created_by": "${workerId}",
          "updated_by": "${workerId}"
        }
      }
    }'::jsonb,
    load_target_ref = 'jdbc_mapped',
    updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'ta'
  AND template_code IN ('TA_IMPORT_CUSTOMER_TPL','TA_IMPORT_ORDER_TPL');

UPDATE batch.file_template_config
SET query_param_schema = '{
      "jdbcMappedImport": {
        "schema": "biz",
        "table": "transaction",
        "tenantColumn": "tenant_id",
        "columnMappings": [
          {"from": "txn_no", "to": "txn_no"},
          {"from": "account_no", "to": "account_no"},
          {"from": "txn_type", "to": "txn_type"},
          {"from": "amount", "to": "amount"},
          {"from": "currency_code", "to": "currency_code"},
          {"from": "txn_date", "to": "txn_date"},
          {"from": "remark", "to": "remark"}
        ],
        "conflictColumns": ["tenant_id", "txn_no"]
      }
    }'::jsonb,
    load_target_ref = 'jdbc_mapped',
    updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'tb'
  AND template_code = 'TB_IMPORT_TRANSACTION_TPL';

UPDATE batch.file_template_config
SET query_param_schema = '{
      "jdbcMappedImport": {
        "schema": "biz",
        "table": "risk_score",
        "tenantColumn": "tenant_id",
        "columnMappings": [
          {"from": "entity_id", "to": "entity_id"},
          {"from": "entity_type", "to": "entity_type"},
          {"from": "score_value", "to": "score_value"},
          {"from": "score_band", "to": "score_band"},
          {"from": "score_date", "to": "score_date"}
        ],
        "conflictColumns": ["tenant_id", "entity_id", "score_date"]
      }
    }'::jsonb,
    load_target_ref = 'jdbc_mapped',
    updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'tc'
  AND template_code = 'TC_IMPORT_RISK_SCORE_TPL';

UPDATE batch.file_template_config
SET default_query_sql = 'SELECT id, tenant_id, customer_no, customer_name, customer_type, certificate_no, mobile_no, email, status FROM biz.customer_account WHERE tenant_id = :tenantId AND (:batchNo IS NULL OR :batchNo IS NOT NULL)',
    query_param_schema = '{"export_data_ref":"sql_template_export","sqlTemplateExport":{"cursorColumn":"id"}}'::jsonb,
    export_data_ref = 'sql_template_export',
    updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'ta'
  AND template_code = 'TA_EXPORT_REPORT_TPL';

UPDATE batch.file_template_config
SET default_query_sql = 'SELECT id, tenant_id, txn_no, account_no, txn_type, amount, currency_code, txn_date, remark FROM biz.transaction WHERE tenant_id = :tenantId AND (:batchNo IS NULL OR :batchNo IS NOT NULL)',
    query_param_schema = '{"export_data_ref":"sql_template_export","sqlTemplateExport":{"cursorColumn":"id"}}'::jsonb,
    export_data_ref = 'sql_template_export',
    updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'tb'
  AND template_code = 'TB_EXPORT_STATEMENT_TPL';

UPDATE batch.file_template_config
SET default_query_sql = 'SELECT id, tenant_id, entity_id, entity_type, score_value, score_band, score_date FROM biz.risk_score WHERE tenant_id = :tenantId AND (:batchNo IS NULL OR :batchNo IS NOT NULL)',
    query_param_schema = '{"export_data_ref":"sql_template_export","sqlTemplateExport":{"cursorColumn":"id"}}'::jsonb,
    export_data_ref = 'sql_template_export',
    updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'tc'
  AND template_code = 'TC_EXPORT_RISK_ALERT_TPL';

UPDATE batch.job_definition
SET default_params = m.params,
    updated_at = CURRENT_TIMESTAMP
FROM (
    VALUES
      ('ta', 'TA_IMPORT_CUSTOMER',
        jsonb_build_object(
          'templateCode', 'TA_IMPORT_CUSTOMER_TPL',
          'content', 'customer_no,customer_name,customer_type,certificate_no,mobile_no,email,status' || E'\n'
                     || 'WF-CUST-000001,工作流客户1,PERSONAL,WFID000001,13900000001,wf1@sim.com,ACTIVE' || E'\n')),
      ('ta', 'TA_IMPORT_ORDER',
        jsonb_build_object(
          'templateCode', 'TA_IMPORT_ORDER_TPL',
          'content', 'customer_no,customer_name,customer_type,certificate_no,mobile_no,email,status' || E'\n'
                     || 'WF-ORDER-000001,工作流订单客户1,PERSONAL,WFOD000001,13900000002,wfo1@sim.com,ACTIVE' || E'\n')),
      ('ta', 'TA_EXPORT_REPORT', jsonb_build_object('templateCode', 'TA_EXPORT_REPORT_TPL')),
      ('tb', 'TB_IMPORT_TRANSACTION',
        jsonb_build_object(
          'templateCode', 'TB_IMPORT_TRANSACTION_TPL',
          'content', 'txn_no,account_no,txn_type,amount,currency_code,txn_date,remark' || E'\n'
                     || 'WF-TB-TXN-000001,WFACC000001,DEPOSIT,101.00,CNY,2026-06-08,wf-default' || E'\n')),
      ('tb', 'TB_EXPORT_STATEMENT', jsonb_build_object('templateCode', 'TB_EXPORT_STATEMENT_TPL')),
      ('tc', 'TC_IMPORT_RISK_SCORE',
        jsonb_build_object(
          'templateCode', 'TC_IMPORT_RISK_SCORE_TPL',
          'content', 'entity_id,entity_type,score_value,score_band,score_date' || E'\n'
                     || 'WF-ENT-000001,ACCOUNT,701,MEDIUM,2026-06-08' || E'\n')),
      ('tc', 'TC_EXPORT_RISK_ALERT', jsonb_build_object('templateCode', 'TC_EXPORT_RISK_ALERT_TPL'))
) AS m(tenant_id, job_code, params)
WHERE batch.job_definition.tenant_id = m.tenant_id
  AND batch.job_definition.job_code = m.job_code;

UPDATE batch.pipeline_step_definition AS psd
SET step_order = m.step_order,
    updated_at = CURRENT_TIMESTAMP
FROM batch.pipeline_definition pd,
     (VALUES
       ('IMPORT', 'RECEIVE',    1),
       ('IMPORT', 'PREPROCESS', 2),
       ('IMPORT', 'PARSE',      3),
       ('IMPORT', 'VALIDATE',   4),
       ('IMPORT', 'LOAD',       5),
       ('IMPORT', 'FEEDBACK',   6),
       ('EXPORT', 'PREPARE',    1),
       ('EXPORT', 'GENERATE',   2),
       ('EXPORT', 'STORE',      3),
       ('EXPORT', 'REGISTER',   4),
       ('EXPORT', 'COMPLETE',   5),
       ('DISPATCH', 'PREPARE',  1),
       ('DISPATCH', 'DISPATCH', 2),
       ('DISPATCH', 'DELIVER',  2),
       ('DISPATCH', 'ACK',      3),
       ('DISPATCH', 'RETRY',    4),
       ('DISPATCH', 'COMPENSATE', 5),
       ('DISPATCH', 'COMPLETE', 6)
     ) AS m(pipeline_type, stage_code, step_order)
WHERE psd.pipeline_definition_id = pd.id
  AND pd.tenant_id IN ('ta','tb','tc')
  AND pd.pipeline_type = m.pipeline_type
  AND psd.stage_code = m.stage_code;

-- Citus:distributed UPDATE 的 SET 含 CASE(分布键 join)被判 non-IMMUTABLE-in-CASE 直接拒绝。
-- 此条仅 DISPATCH pipeline 的 step 路由补丁,import/export/process 阶段用不到;
-- 用 DO 块包成 best-effort,Citus 报错只 NOTICE 不中断 ON_ERROR_STOP(普通 PG 正常执行,双栈安全)。
DO $dispatch_step_route$
BEGIN
  UPDATE batch.pipeline_step_definition AS psd
  SET step_params =
        CASE psd.stage_code
          WHEN 'ACK' THEN coalesce(psd.step_params, '{}'::jsonb)
              || jsonb_build_object('onSuccessNextStageCode', 'COMPLETE')
          WHEN 'RETRY' THEN coalesce(psd.step_params, '{}'::jsonb)
              || jsonb_build_object('onFailureNextStageCode', 'COMPENSATE')
          WHEN 'COMPENSATE' THEN coalesce(psd.step_params, '{}'::jsonb)
              || jsonb_build_object('terminalOnSuccess', true)
          WHEN 'COMPLETE' THEN coalesce(psd.step_params, '{}'::jsonb)
              || jsonb_build_object('terminalOnSuccess', true)
          ELSE coalesce(psd.step_params, '{}'::jsonb)
        END
  FROM batch.pipeline_definition pd
  WHERE psd.pipeline_definition_id = pd.id
    AND pd.tenant_id IN ('ta','tb','tc')
    AND pd.pipeline_type = 'DISPATCH'
    AND psd.stage_code IN ('ACK','RETRY','COMPENSATE','COMPLETE');
EXCEPTION WHEN OTHERS THEN
  RAISE NOTICE 'DISPATCH step route patch skipped (Citus dialect): %', SQLERRM;
END $dispatch_step_route$;

-- Citus:distributed UPDATE ... FROM (VALUES) 需 m.tenant_id 做分布键 join,本地 VALUES
-- 触发 recursive planning;此条仅 DISPATCH api_push 渠道端点改写,import/export/process 用不到。
-- 用 DO 块包成 best-effort,双栈安全(普通 PG 正常执行,Citus 报错只 NOTICE)。
DO $api_push_endpoint$
BEGIN
  UPDATE batch.file_channel_config
  SET target_endpoint = m.endpoint,
      config_json = (coalesce(config_json, '{}'::jsonb) - 'url' - 'method' - 'tokenHeader')
          || jsonb_build_object(
               'target_endpoint', m.endpoint,
               'api_push_api_key', 'sim-api-key',
               'authorization', 'Bearer sim-token'
             ),
      updated_at = CURRENT_TIMESTAMP
  FROM (
      VALUES
        ('tb', 'tb_api_push',      'http://localhost:11080/tb/callback'),
        ('tb', 'tb_api_ingest',    'http://localhost:11080/tb/ingest'),
        ('tc', 'tc_api_risk_push', 'http://localhost:11080/tc/ingest')
  ) AS m(tenant_id, channel_code, endpoint)
  WHERE batch.file_channel_config.tenant_id = m.tenant_id
    AND batch.file_channel_config.channel_code = m.channel_code;
EXCEPTION WHEN OTHERS THEN
  RAISE NOTICE 'api_push endpoint patch skipped (Citus dialect): %', SQLERRM;
END $api_push_endpoint$;

-- ----------------------------------------------------------------------------
-- 10. Stage 2 import 业务分支:补 XML / FIXED_WIDTH 可触发系统级模板与 job。
--     这些 job 不进入 11-sheet fixture,由 bootstrap 幂等补齐,用于本地业务矩阵验证。
-- ----------------------------------------------------------------------------
INSERT INTO batch.file_template_config (
    tenant_id, template_code, template_name, template_type, biz_type,
    file_format_type, charset, target_charset, with_bom, line_separator,
    delimiter, quote_char, escape_char, record_length, header_rows, footer_rows,
    header_template, trailer_template, checksum_type, compress_type, encrypt_type,
    naming_rule, field_mappings, validation_rule_set, query_param_schema,
    streaming_enabled, page_size, fetch_size, chunk_size, enabled, version,
    description, created_by, updated_by, preprocess_pipeline,
    preview_masking_enabled, error_line_masking_enabled, log_masking_enabled,
    content_encryption_enabled, download_requires_approval, load_target_ref, is_deleted
)
VALUES
  ('ta', 'TA_IMPORT_CUSTOMER_XML_TPL', '客户 XML 导入模板', 'IMPORT', 'CUSTOMER_XML',
   'XML', 'UTF-8', 'UTF-8', false, E'\n',
   null, null, null, 0, 0, 0,
   '{}'::jsonb, '{}'::jsonb, 'NONE', 'NONE', 'NONE',
   'ta_import_customer_xml_${batchDate}.xml',
   '[]'::jsonb,
   jsonb_build_object(
     'nullCheck', jsonb_build_object('enabled', true, 'fields',
       jsonb_build_array('customer_no', 'customer_name', 'status')),
     'fieldRules', jsonb_build_object('status',
       jsonb_build_object('allowedValues', jsonb_build_array('ACTIVE', 'INACTIVE')))),
   jsonb_build_object(
     'parseHints', jsonb_build_object('xmlRecordElement', 'customer'),
     'jdbcMappedImport', jsonb_build_object(
       'schema', 'biz',
       'table', 'customer_account',
       'tenantColumn', 'tenant_id',
       'columnMappings', jsonb_build_array(
         jsonb_build_object('from', 'customer_no', 'to', 'customer_no'),
         jsonb_build_object('from', 'customer_name', 'to', 'customer_name'),
         jsonb_build_object('from', 'customer_type', 'to', 'customer_type'),
         jsonb_build_object('from', 'certificate_no', 'to', 'certificate_no'),
         jsonb_build_object('from', 'mobile_no', 'to', 'mobile_no'),
         jsonb_build_object('from', 'email', 'to', 'email'),
         jsonb_build_object('from', 'status', 'to', 'status')),
       'conflictColumns', jsonb_build_array('tenant_id', 'customer_no'),
       'systemBindings', jsonb_build_object(
         'source_file_name', '${sourceFileName}',
         'source_batch_no', '${batchNo}',
         'source_trace_id', '${traceId}',
         'created_by', '${workerId}',
         'updated_by', '${workerId}'))),
   true, 1000, 1000, 500, true, 1,
   'Stage 2 XML import system scenario', 'sim-e2e', 'sim-e2e', null,
   false, false, true, false, false, 'jdbc_mapped', false),
  ('ta', 'TA_IMPORT_CUSTOMER_FIXED_TPL', '客户 FIXED_WIDTH 导入模板', 'IMPORT', 'CUSTOMER_FIXED',
   'FIXED_WIDTH', 'UTF-8', 'UTF-8', false, E'\n',
   null, null, null, 86, 0, 0,
   '{}'::jsonb, '{}'::jsonb, 'NONE', 'NONE', 'NONE',
   'ta_import_customer_fixed_${batchDate}.txt',
   jsonb_build_array(
     jsonb_build_object('target', 'customer_no', 'start', 0, 'length', 12),
     jsonb_build_object('target', 'customer_name', 'start', 12, 'length', 20),
     jsonb_build_object('target', 'customer_type', 'start', 32, 'length', 10),
     jsonb_build_object('target', 'certificate_no', 'start', 42, 'length', 16),
     jsonb_build_object('target', 'mobile_no', 'start', 58, 'length', 12),
     jsonb_build_object('target', 'email', 'start', 70, 'length', 8),
     jsonb_build_object('target', 'status', 'start', 78, 'length', 8)),
   jsonb_build_object(
     'nullCheck', jsonb_build_object('enabled', true, 'fields',
       jsonb_build_array('customer_no', 'customer_name', 'status')),
     'fieldRules', jsonb_build_object('status',
       jsonb_build_object('allowedValues', jsonb_build_array('ACTIVE', 'INACTIVE')))),
   jsonb_build_object(
     'jdbcMappedImport', jsonb_build_object(
       'schema', 'biz',
       'table', 'customer_account',
       'tenantColumn', 'tenant_id',
       'columnMappings', jsonb_build_array(
         jsonb_build_object('from', 'customer_no', 'to', 'customer_no'),
         jsonb_build_object('from', 'customer_name', 'to', 'customer_name'),
         jsonb_build_object('from', 'customer_type', 'to', 'customer_type'),
         jsonb_build_object('from', 'certificate_no', 'to', 'certificate_no'),
         jsonb_build_object('from', 'mobile_no', 'to', 'mobile_no'),
         jsonb_build_object('from', 'email', 'to', 'email'),
         jsonb_build_object('from', 'status', 'to', 'status')),
       'conflictColumns', jsonb_build_array('tenant_id', 'customer_no'),
       'systemBindings', jsonb_build_object(
         'source_file_name', '${sourceFileName}',
         'source_batch_no', '${batchNo}',
         'source_trace_id', '${traceId}',
         'created_by', '${workerId}',
         'updated_by', '${workerId}'))),
   true, 1000, 1000, 500, true, 1,
   'Stage 2 fixed-width import system scenario', 'sim-e2e', 'sim-e2e', null,
   false, false, true, false, false, 'jdbc_mapped', false)
ON CONFLICT (tenant_id, template_code, version) DO UPDATE
SET template_name = EXCLUDED.template_name,
    biz_type = EXCLUDED.biz_type,
    file_format_type = EXCLUDED.file_format_type,
    charset = EXCLUDED.charset,
    target_charset = EXCLUDED.target_charset,
    line_separator = EXCLUDED.line_separator,
    record_length = EXCLUDED.record_length,
    header_rows = EXCLUDED.header_rows,
    footer_rows = EXCLUDED.footer_rows,
    naming_rule = EXCLUDED.naming_rule,
    field_mappings = EXCLUDED.field_mappings,
    validation_rule_set = EXCLUDED.validation_rule_set,
    query_param_schema = EXCLUDED.query_param_schema,
    chunk_size = EXCLUDED.chunk_size,
    enabled = EXCLUDED.enabled,
    description = EXCLUDED.description,
    updated_by = EXCLUDED.updated_by,
    updated_at = EXCLUDED.updated_at,
    load_target_ref = EXCLUDED.load_target_ref,
    is_deleted = false;

INSERT INTO batch.job_definition (
    tenant_id, job_code, job_name, job_type, biz_type, schedule_type, schedule_expr,
    timezone, priority, queue_code, worker_group, calendar_code, window_code,
    trigger_mode, dag_enabled, shard_strategy, retry_policy, retry_max_count,
    timeout_seconds, execution_handler, param_schema, default_params, version,
    enabled, description, created_by, updated_by, execution_mode,
    previous_day_dependency_scope, retry_policy_by_class
)
-- Citus:INSERT INTO dist SELECT FROM dist CROSS JOIN (VALUES) 会静默插 0 行
-- (含本地 VALUES 的 distributed INSERT...SELECT 不下推)。展开成每变体一条
-- co-located INSERT...SELECT(源/目标同表同分布键,可下推),双栈等价。
SELECT src.tenant_id, 'TA_IMPORT_CUSTOMER_XML', '客户 XML 导入', src.job_type, 'CUSTOMER_XML',
       'MANUAL', null, src.timezone, src.priority, src.queue_code, src.worker_group,
       src.calendar_code, src.window_code, 'API', false, src.shard_strategy,
       'NONE', 0, src.timeout_seconds, src.execution_handler,
       src.param_schema, jsonb_build_object('templateCode', 'TA_IMPORT_CUSTOMER_XML_TPL'),
       1, true, 'Stage 2 XML import system scenario', 'sim-e2e', 'sim-e2e', 'FULL',
       coalesce(src.previous_day_dependency_scope, 'INHERIT'), src.retry_policy_by_class
FROM batch.job_definition src
WHERE src.tenant_id = 'ta'
  AND src.job_code = 'TA_IMPORT_CUSTOMER'
ON CONFLICT (tenant_id, job_code) DO UPDATE
SET job_name = EXCLUDED.job_name,
    biz_type = EXCLUDED.biz_type,
    schedule_type = EXCLUDED.schedule_type,
    schedule_expr = EXCLUDED.schedule_expr,
    trigger_mode = EXCLUDED.trigger_mode,
    retry_policy = EXCLUDED.retry_policy,
    retry_max_count = EXCLUDED.retry_max_count,
    default_params = EXCLUDED.default_params,
    enabled = true,
    description = EXCLUDED.description,
    updated_by = EXCLUDED.updated_by,
    updated_at = EXCLUDED.updated_at,
    execution_mode = 'FULL';

INSERT INTO batch.job_definition (
    tenant_id, job_code, job_name, job_type, biz_type, schedule_type, schedule_expr,
    timezone, priority, queue_code, worker_group, calendar_code, window_code,
    trigger_mode, dag_enabled, shard_strategy, retry_policy, retry_max_count,
    timeout_seconds, execution_handler, param_schema, default_params, version,
    enabled, description, created_by, updated_by, execution_mode,
    previous_day_dependency_scope, retry_policy_by_class
)
SELECT src.tenant_id, 'TA_IMPORT_CUSTOMER_FIXED', '客户 FIXED_WIDTH 导入', src.job_type, 'CUSTOMER_FIXED',
       'MANUAL', null, src.timezone, src.priority, src.queue_code, src.worker_group,
       src.calendar_code, src.window_code, 'API', false, src.shard_strategy,
       'NONE', 0, src.timeout_seconds, src.execution_handler,
       src.param_schema, jsonb_build_object('templateCode', 'TA_IMPORT_CUSTOMER_FIXED_TPL'),
       1, true, 'Stage 2 fixed-width import system scenario', 'sim-e2e', 'sim-e2e', 'FULL',
       coalesce(src.previous_day_dependency_scope, 'INHERIT'), src.retry_policy_by_class
FROM batch.job_definition src
WHERE src.tenant_id = 'ta'
  AND src.job_code = 'TA_IMPORT_CUSTOMER'
ON CONFLICT (tenant_id, job_code) DO UPDATE
SET job_name = EXCLUDED.job_name,
    biz_type = EXCLUDED.biz_type,
    schedule_type = EXCLUDED.schedule_type,
    schedule_expr = EXCLUDED.schedule_expr,
    trigger_mode = EXCLUDED.trigger_mode,
    retry_policy = EXCLUDED.retry_policy,
    retry_max_count = EXCLUDED.retry_max_count,
    default_params = EXCLUDED.default_params,
    enabled = true,
    description = EXCLUDED.description,
    updated_by = EXCLUDED.updated_by,
    updated_at = EXCLUDED.updated_at,
    execution_mode = 'FULL';

INSERT INTO batch.pipeline_definition (
    tenant_id, job_code, pipeline_name, pipeline_type, biz_type, worker_group,
    version, enabled, description
)
-- Citus:同上,CROSS JOIN (VALUES) 展开为每变体一条 co-located INSERT...SELECT
SELECT src.tenant_id, 'TA_IMPORT_CUSTOMER_XML', '客户 XML 导入流水线', src.pipeline_type, 'CUSTOMER_XML',
       src.worker_group, 1, true, 'Stage 2 XML import system scenario'
FROM batch.pipeline_definition src
WHERE src.tenant_id = 'ta'
  AND src.job_code = 'TA_IMPORT_CUSTOMER'
ON CONFLICT (tenant_id, job_code, version) DO UPDATE
SET pipeline_name = EXCLUDED.pipeline_name,
    biz_type = EXCLUDED.biz_type,
    worker_group = EXCLUDED.worker_group,
    enabled = true,
    description = EXCLUDED.description,
    updated_at = EXCLUDED.updated_at;

INSERT INTO batch.pipeline_definition (
    tenant_id, job_code, pipeline_name, pipeline_type, biz_type, worker_group,
    version, enabled, description
)
SELECT src.tenant_id, 'TA_IMPORT_CUSTOMER_FIXED', '客户 FIXED_WIDTH 导入流水线', src.pipeline_type, 'CUSTOMER_FIXED',
       src.worker_group, 1, true, 'Stage 2 fixed-width import system scenario'
FROM batch.pipeline_definition src
WHERE src.tenant_id = 'ta'
  AND src.job_code = 'TA_IMPORT_CUSTOMER'
ON CONFLICT (tenant_id, job_code, version) DO UPDATE
SET pipeline_name = EXCLUDED.pipeline_name,
    biz_type = EXCLUDED.biz_type,
    worker_group = EXCLUDED.worker_group,
    enabled = true,
    description = EXCLUDED.description,
    updated_at = EXCLUDED.updated_at;

WITH source_steps AS (
    SELECT psd.*
    FROM batch.pipeline_definition src_pd
    JOIN batch.pipeline_step_definition psd ON psd.pipeline_definition_id = src_pd.id
    WHERE src_pd.tenant_id = 'ta'
      AND src_pd.job_code = 'TA_IMPORT_CUSTOMER'
), target_pipelines AS (
    SELECT pd.id AS pipeline_definition_id
    FROM batch.pipeline_definition pd
    WHERE pd.tenant_id = 'ta'
      AND pd.job_code IN ('TA_IMPORT_CUSTOMER_XML', 'TA_IMPORT_CUSTOMER_FIXED')
)
INSERT INTO batch.pipeline_step_definition (
    pipeline_definition_id, step_code, step_name, stage_code, step_order,
    impl_code, step_params, timeout_seconds, retry_policy, retry_max_count, enabled
)
SELECT tp.pipeline_definition_id, ss.step_code, ss.step_name, ss.stage_code, ss.step_order,
       ss.impl_code, coalesce(ss.step_params, '{}'::jsonb), ss.timeout_seconds,
       ss.retry_policy, ss.retry_max_count, ss.enabled
FROM target_pipelines tp
CROSS JOIN source_steps ss
ON CONFLICT (pipeline_definition_id, step_code) DO UPDATE
SET step_name = EXCLUDED.step_name,
    stage_code = EXCLUDED.stage_code,
    step_order = EXCLUDED.step_order,
    impl_code = EXCLUDED.impl_code,
    step_params = EXCLUDED.step_params,
    timeout_seconds = EXCLUDED.timeout_seconds,
    retry_policy = EXCLUDED.retry_policy,
    retry_max_count = EXCLUDED.retry_max_count,
    enabled = EXCLUDED.enabled,
    updated_at = EXCLUDED.updated_at;

-- ----------------------------------------------------------------------------
-- 11. Stage 3 export 业务分支:补 JSON / FIXED_WIDTH / EXCEL / bad SQL 模板。
--     复用 TA_EXPORT_REPORT job,通过 params.templateCode 切换模板,避免增加 fixture job 数量。
-- ----------------------------------------------------------------------------
WITH source_template AS (
    SELECT *
    FROM batch.file_template_config
    WHERE tenant_id = 'ta'
      AND template_code = 'TA_EXPORT_REPORT_TPL'
      AND version = 1
), export_matrix AS (
    SELECT *
    FROM (VALUES
      (
        'TA_EXPORT_REPORT_JSON_TPL',
        '客户 JSON 导出模板',
        'TA_EXPORT_REPORT_JSON',
        'JSON',
        'ta_export_customer_json_${bizDate}_${batchNo}.json',
        0,
        'SELECT id, tenant_id, customer_no, customer_name, customer_type, certificate_no, mobile_no, email, status FROM biz.customer_account WHERE tenant_id = :tenantId AND customer_no LIKE ''EXP-%'' AND (:batchNo IS NOT NULL)',
        '{"export_data_ref":"sql_template_export","sqlTemplateExport":{"cursorColumn":"id"}}'::jsonb,
        'Stage 3 JSON export system scenario'
      ),
      (
        'TA_EXPORT_REPORT_FIXED_TPL',
        '客户 FIXED_WIDTH 导出模板',
        'TA_EXPORT_REPORT_FIXED',
        'FIXED_WIDTH',
        'ta_export_customer_fixed_${bizDate}_${batchNo}.txt',
        88,
        'SELECT id, tenant_id, customer_no, customer_name, customer_type, certificate_no, mobile_no, email, status FROM biz.customer_account WHERE tenant_id = :tenantId AND customer_no LIKE ''EXP-%'' AND (:batchNo IS NOT NULL)',
        jsonb_build_object(
          'export_data_ref', 'sql_template_export',
          'sqlTemplateExport', jsonb_build_object('cursorColumn', 'id'),
          'fixedWidthColumns', jsonb_build_array(
            jsonb_build_object('header', 'customer_no', 'source', 'detail.customer_no', 'width', 16),
            jsonb_build_object('header', 'customer_name', 'source', 'detail.customer_name', 'width', 24),
            jsonb_build_object('header', 'customer_type', 'source', 'detail.customer_type', 'width', 12),
            jsonb_build_object('header', 'mobile_no', 'source', 'detail.mobile_no', 'width', 14),
            jsonb_build_object('header', 'status', 'source', 'detail.status', 'width', 10))),
        'Stage 3 fixed-width export system scenario'
      ),
      (
        'TA_EXPORT_REPORT_EXCEL_TPL',
        '客户 EXCEL 导出模板',
        'TA_EXPORT_REPORT_EXCEL',
        'EXCEL',
        'ta_export_customer_excel_${bizDate}_${batchNo}.xlsx',
        0,
        'SELECT id, tenant_id, customer_no, customer_name, customer_type, certificate_no, mobile_no, email, status FROM biz.customer_account WHERE tenant_id = :tenantId AND customer_no LIKE ''EXP-%'' AND (:batchNo IS NOT NULL)',
        jsonb_build_object(
          'export_data_ref', 'sql_template_export',
          'sqlTemplateExport', jsonb_build_object('cursorColumn', 'id'),
          'csvColumns', jsonb_build_array(
            jsonb_build_object('header', 'customer_no', 'source', 'detail.customer_no'),
            jsonb_build_object('header', 'customer_name', 'source', 'detail.customer_name'),
            jsonb_build_object('header', 'customer_type', 'source', 'detail.customer_type'),
            jsonb_build_object('header', 'mobile_no', 'source', 'detail.mobile_no'),
            jsonb_build_object('header', 'status', 'source', 'detail.status'))),
        'Stage 3 Excel export system scenario'
      ),
      (
        'TA_EXPORT_REPORT_BAD_SQL_TPL',
        '客户坏 SQL 导出模板',
        'TA_EXPORT_REPORT_BAD_SQL',
        'JSON',
        'ta_export_customer_bad_sql_${bizDate}_${batchNo}.json',
        0,
        'SELECT id, missing_col FROM biz.customer_account WHERE tenant_id = :tenantId AND customer_no LIKE ''EXP-%'' AND (:batchNo IS NOT NULL)',
        '{"export_data_ref":"sql_template_export","sqlTemplateExport":{"cursorColumn":"id"}}'::jsonb,
        'Stage 3 bad SQL export failure scenario'
      )
    ) AS m(template_code, template_name, biz_type, file_format_type, naming_rule,
           record_length, default_query_sql, query_param_schema, description)
)
INSERT INTO batch.file_template_config (
    tenant_id, template_code, template_name, template_type, biz_type,
    file_format_type, charset, target_charset, with_bom, line_separator,
    delimiter, quote_char, escape_char, record_length, header_rows, footer_rows,
    header_template, trailer_template, checksum_type, compress_type, encrypt_type,
    naming_rule, field_mappings, validation_rule_set, default_query_code,
    default_query_sql, query_param_schema, streaming_enabled, page_size, fetch_size,
    chunk_size, enabled, version, description, created_by, updated_by,
    preprocess_pipeline, preview_masking_enabled, error_line_masking_enabled,
    log_masking_enabled, content_encryption_enabled, encryption_key_ref,
    download_requires_approval, masking_rule_set, export_data_ref, load_target_ref, is_deleted
)
SELECT
    st.tenant_id, em.template_code, em.template_name, 'EXPORT', em.biz_type,
    em.file_format_type, st.charset, st.target_charset, st.with_bom, st.line_separator,
    st.delimiter, st.quote_char, st.escape_char, em.record_length, 1, st.footer_rows,
    st.header_template, st.trailer_template, st.checksum_type, st.compress_type, st.encrypt_type,
    em.naming_rule, '[]'::jsonb, st.validation_rule_set, st.default_query_code,
    em.default_query_sql, em.query_param_schema, true, 200, 200,
    100, true, 1, em.description, 'sim-e2e', 'sim-e2e',
    st.preprocess_pipeline, st.preview_masking_enabled, st.error_line_masking_enabled,
    st.log_masking_enabled, st.content_encryption_enabled, st.encryption_key_ref,
    st.download_requires_approval, st.masking_rule_set, 'sql_template_export', null, false
FROM source_template st
CROSS JOIN export_matrix em
ON CONFLICT (tenant_id, template_code, version) DO UPDATE
SET template_name = EXCLUDED.template_name,
    template_type = EXCLUDED.template_type,
    biz_type = EXCLUDED.biz_type,
    file_format_type = EXCLUDED.file_format_type,
    record_length = EXCLUDED.record_length,
    header_rows = EXCLUDED.header_rows,
    naming_rule = EXCLUDED.naming_rule,
    field_mappings = EXCLUDED.field_mappings,
    default_query_sql = EXCLUDED.default_query_sql,
    query_param_schema = EXCLUDED.query_param_schema,
    streaming_enabled = EXCLUDED.streaming_enabled,
    page_size = EXCLUDED.page_size,
    fetch_size = EXCLUDED.fetch_size,
    chunk_size = EXCLUDED.chunk_size,
    enabled = EXCLUDED.enabled,
    description = EXCLUDED.description,
    updated_by = EXCLUDED.updated_by,
    updated_at = EXCLUDED.updated_at,
    export_data_ref = EXCLUDED.export_data_ref,
    load_target_ref = EXCLUDED.load_target_ref,
    is_deleted = false;

COMMIT;

-- ----------------------------------------------------------------------------
-- 校验查询(诊断用,自行复制到 psql 跑)
-- ----------------------------------------------------------------------------
-- SELECT tenant_id, status FROM batch.tenant WHERE tenant_id IN ('ta','tb','tc');
-- SELECT tenant_id, job_code, execution_mode FROM batch.job_definition
--  WHERE tenant_id IN ('ta','tb','tc') ORDER BY 1,2;
-- SELECT pd.tenant_id, pd.job_code, pd.pipeline_type,
--        string_agg(psd.stage_code, ',' ORDER BY psd.step_order) AS stages,
--        string_agg(psd.impl_code,  ',' ORDER BY psd.step_order) AS impls
--   FROM batch.pipeline_definition pd
--   JOIN batch.pipeline_step_definition psd ON psd.pipeline_definition_id = pd.id
--  WHERE pd.tenant_id IN ('ta','tb','tc')
--  GROUP BY 1,2,3 ORDER BY 1,2;
-- SELECT tenant_id, channel_code, config_json FROM batch.file_channel_config
--  WHERE tenant_id IN ('ta','tb','tc') AND channel_type='SFTP';
-- SELECT tenant_id, template_code, template_type,
--        left(default_query_sql, 80) AS sql_head,
--        query_param_schema->>'spec' AS spec
--   FROM batch.file_template_config
--  WHERE tenant_id IN ('ta','tb','tc') ORDER BY 1,2;
