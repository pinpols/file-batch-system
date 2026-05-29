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
INSERT INTO batch.tenant (tenant_id, tenant_name, status, description, created_by)
VALUES
    ('ta', 'ta', 'ACTIVE', 'sim-e2e bootstrap (A4 fixture engineering)', 'sim-e2e'),
    ('tb', 'tb', 'ACTIVE', 'sim-e2e bootstrap (A4 fixture engineering)', 'sim-e2e'),
    ('tc', 'tc', 'ACTIVE', 'sim-e2e bootstrap (A4 fixture engineering)', 'sim-e2e')
ON CONFLICT (tenant_id) DO UPDATE
SET status      = 'ACTIVE',
    description = COALESCE(batch.tenant.description, EXCLUDED.description),
    updated_at  = CURRENT_TIMESTAMP;

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
  AND (config_json ? 'host' OR config_json ? 'port' OR config_json ? 'username'
       OR config_json ? 'password' OR config_json ? 'remotePath'
       OR config_json ? 'sftpHost' OR config_json ? 'sftpPort'
       OR config_json ? 'sftpUsername' OR config_json ? 'sftpPassword'
       OR config_json ? 'sftpRemotePath' OR config_json ? 'remote_path');

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
