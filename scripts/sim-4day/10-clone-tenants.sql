-- ADR-sim 4day · P1 把 ta/tb/tc 的「活配置」克隆到 t04..t10,凑 10 租户。
-- 仅换 tenant_id(job/template/channel/workflow code 保持不变,多租隔离天然成立),
-- 子表 FK 走自然 code 键重映射(workflow_edge 用 node code,不需 node id 重映射)。
-- 幂等:每次先删 dst 配置再插。只动 config,不碰运行时。
SET search_path TO batch;

-- 通用:克隆一张「带 tenant_id、无被引用 id」的租户表(排除 id,tenant_id 覆盖为 dst)
CREATE OR REPLACE FUNCTION batch._clone_tbl(p_tbl text, p_src text, p_dst text) RETURNS void AS $$
DECLARE cols text; sel text;
BEGIN
  SELECT string_agg(quote_ident(column_name), ', ' ORDER BY ordinal_position),
         string_agg(CASE WHEN column_name='tenant_id' THEN quote_literal(p_dst)
                         ELSE quote_ident(column_name) END, ', ' ORDER BY ordinal_position)
    INTO cols, sel
  FROM information_schema.columns
  WHERE table_schema='batch' AND table_name=p_tbl AND column_name <> 'id';
  EXECUTE format('INSERT INTO batch.%I (%s) SELECT %s FROM batch.%I WHERE tenant_id=%L',
                 p_tbl, cols, sel, p_tbl, p_src);
END $$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION batch.clone_tenant(p_src text, p_dst text, p_name text) RETURNS void AS $$
BEGIN
  -- 1) 清 dst 旧配置(子→父)
  DELETE FROM batch.workflow_edge WHERE tenant_id=p_dst;
  DELETE FROM batch.workflow_node WHERE tenant_id=p_dst;
  DELETE FROM batch.workflow_definition_version WHERE tenant_id=p_dst;
  DELETE FROM batch.workflow_definition WHERE tenant_id=p_dst;
  DELETE FROM batch.pipeline_step_definition WHERE pipeline_definition_id IN
    (SELECT id FROM batch.pipeline_definition WHERE tenant_id=p_dst);
  DELETE FROM batch.pipeline_definition WHERE tenant_id=p_dst;
  DELETE FROM batch.file_channel_config WHERE tenant_id=p_dst;
  DELETE FROM batch.file_template_config WHERE tenant_id=p_dst;
  DELETE FROM batch.job_definition WHERE tenant_id=p_dst;
  DELETE FROM batch.calendar_holiday WHERE tenant_id=p_dst;
  DELETE FROM batch.business_calendar WHERE tenant_id=p_dst;
  DELETE FROM batch.batch_window WHERE tenant_id=p_dst;
  DELETE FROM batch.tenant_quota_policy WHERE tenant_id=p_dst;
  DELETE FROM batch.resource_queue WHERE tenant_id=p_dst;
  DELETE FROM batch.tenant WHERE tenant_id=p_dst;

  -- 2) tenant 行
  INSERT INTO batch.tenant (tenant_id, tenant_name, status, description, created_at, updated_at, created_by)
  SELECT p_dst, p_name, COALESCE(status,'ACTIVE'), 'sim-4day clone of '||p_src, now(), now(), 'sim-4day'
  FROM batch.tenant WHERE tenant_id=p_src;

  -- 3) 简单租户表(父表先于子表:business_calendar / pipeline_definition / workflow_definition 在这批里)
  PERFORM batch._clone_tbl('resource_queue',      p_src, p_dst);
  PERFORM batch._clone_tbl('batch_window',        p_src, p_dst);
  PERFORM batch._clone_tbl('tenant_quota_policy', p_src, p_dst);
  PERFORM batch._clone_tbl('business_calendar',   p_src, p_dst);
  PERFORM batch._clone_tbl('job_definition',      p_src, p_dst);
  PERFORM batch._clone_tbl('file_template_config',p_src, p_dst);
  PERFORM batch._clone_tbl('file_channel_config', p_src, p_dst);
  PERFORM batch._clone_tbl('pipeline_definition', p_src, p_dst);
  PERFORM batch._clone_tbl('workflow_definition', p_src, p_dst);

  -- 4) 子表(FK 走 code 重映射)
  INSERT INTO batch.calendar_holiday
    (calendar_id, biz_date, day_type, holiday_name, description, created_at, updated_at, tenant_id, scope, group_code)
  SELECT dc.id, h.biz_date, h.day_type, h.holiday_name, h.description, h.created_at, h.updated_at, p_dst, h.scope, h.group_code
  FROM batch.calendar_holiday h
  JOIN batch.business_calendar sc ON sc.id=h.calendar_id AND sc.tenant_id=p_src
  JOIN batch.business_calendar dc ON dc.tenant_id=p_dst AND dc.calendar_code=sc.calendar_code;

  INSERT INTO batch.pipeline_step_definition
    (pipeline_definition_id, step_code, step_name, stage_code, step_order, impl_code, step_params,
     timeout_seconds, retry_policy, retry_max_count, enabled, created_at, updated_at)
  SELECT dpd.id, s.step_code, s.step_name, s.stage_code, s.step_order, s.impl_code, s.step_params,
         s.timeout_seconds, s.retry_policy, s.retry_max_count, s.enabled, s.created_at, s.updated_at
  FROM batch.pipeline_step_definition s
  JOIN batch.pipeline_definition spd ON spd.id=s.pipeline_definition_id AND spd.tenant_id=p_src
  JOIN batch.pipeline_definition dpd ON dpd.tenant_id=p_dst AND dpd.job_code=spd.job_code;

  INSERT INTO batch.workflow_node
    (workflow_definition_id, node_code, node_name, node_type, related_job_code, related_pipeline_code,
     worker_group, window_code, node_order, retry_policy, retry_max_count, timeout_seconds, node_params,
     enabled, created_at, updated_at, tenant_id, cross_day_dependencies,
     cross_day_dependency_timeout_seconds, task_timeout_seconds)
  SELECT dwd.id, n.node_code, n.node_name, n.node_type, n.related_job_code, n.related_pipeline_code,
         n.worker_group, n.window_code, n.node_order, n.retry_policy, n.retry_max_count, n.timeout_seconds, n.node_params,
         n.enabled, n.created_at, n.updated_at, p_dst, n.cross_day_dependencies,
         n.cross_day_dependency_timeout_seconds, n.task_timeout_seconds
  FROM batch.workflow_node n
  JOIN batch.workflow_definition swd ON swd.id=n.workflow_definition_id AND swd.tenant_id=p_src
  JOIN batch.workflow_definition dwd ON dwd.tenant_id=p_dst AND dwd.workflow_code=swd.workflow_code;

  INSERT INTO batch.workflow_edge
    (workflow_definition_id, from_node_code, to_node_code, edge_type, condition_expr, enabled, created_at, updated_at, tenant_id)
  SELECT dwd.id, e.from_node_code, e.to_node_code, e.edge_type, e.condition_expr, e.enabled, e.created_at, e.updated_at, p_dst
  FROM batch.workflow_edge e
  JOIN batch.workflow_definition swd ON swd.id=e.workflow_definition_id AND swd.tenant_id=p_src
  JOIN batch.workflow_definition dwd ON dwd.tenant_id=p_dst AND dwd.workflow_code=swd.workflow_code;

  INSERT INTO batch.workflow_definition_version
    (tenant_id, workflow_definition_id, workflow_code, version, workflow_name, workflow_type, enabled,
     nodes_json, edges_json, saved_by, saved_at, summary)
  SELECT p_dst, dwd.id, v.workflow_code, v.version, v.workflow_name, v.workflow_type, v.enabled,
         v.nodes_json, v.edges_json, v.saved_by, v.saved_at, v.summary
  FROM batch.workflow_definition_version v
  JOIN batch.workflow_definition swd ON swd.id=v.workflow_definition_id AND swd.tenant_id=p_src
  JOIN batch.workflow_definition dwd ON dwd.tenant_id=p_dst AND dwd.workflow_code=swd.workflow_code;
END $$ LANGUAGE plpgsql;

-- 执行:10 租户 = ta/tb/tc + 7 克隆。profile 分布:retail×3 / bank×2 / risk×2
SELECT batch.clone_tenant('ta','t04','sim-retail-04');
SELECT batch.clone_tenant('tb','t05','sim-bank-05');
SELECT batch.clone_tenant('tc','t06','sim-risk-06');
SELECT batch.clone_tenant('ta','t07','sim-retail-07');
SELECT batch.clone_tenant('tb','t08','sim-bank-08');
SELECT batch.clone_tenant('tc','t09','sim-risk-09');
SELECT batch.clone_tenant('ta','t10','sim-retail-10');

-- 汇总
SELECT tenant_id,
       (SELECT count(*) FROM batch.job_definition j WHERE j.tenant_id=t.tenant_id) jobs,
       (SELECT count(*) FROM batch.pipeline_definition p WHERE p.tenant_id=t.tenant_id) pipes,
       (SELECT count(*) FROM batch.workflow_definition w WHERE w.tenant_id=t.tenant_id) wfs
FROM (SELECT unnest(ARRAY['ta','tb','tc','t04','t05','t06','t07','t08','t09','t10']) tenant_id) t
ORDER BY tenant_id;
