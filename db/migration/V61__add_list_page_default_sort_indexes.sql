-- =========================================================
-- V61 - 补齐 Console 列表页默认排序缺失的复合索引
--
-- 背景：各 *Mapper.xml 的 selectByQuery 均以 "WHERE tenant_id = ?
-- [可选筛选] ORDER BY ..." 驱动列表分页。V8 里的索引以 enabled /
-- 子类维度为主，没覆盖 "WHERE tenant_id 单条件 + 默认排序 + LIMIT
-- OFFSET" 的最常见路径，2026-04-19 e2e 报告里 /jobs/* 列表超 25s
-- 触发超时即来源于此。这里按每张表的真实 ORDER BY 列追加一条索引。
-- =========================================================

-- ---- 默认按 id DESC（7 张定义/配置表） ----
CREATE INDEX IF NOT EXISTS idx_job_definition_tenant_id_desc
    ON batch.job_definition (tenant_id, id DESC);

CREATE INDEX IF NOT EXISTS idx_pipeline_definition_tenant_id_desc
    ON batch.pipeline_definition (tenant_id, id DESC);

CREATE INDEX IF NOT EXISTS idx_file_channel_tenant_id_desc
    ON batch.file_channel_config (tenant_id, id DESC);

CREATE INDEX IF NOT EXISTS idx_alert_routing_tenant_id_desc
    ON batch.alert_routing_config (tenant_id, id DESC);

CREATE INDEX IF NOT EXISTS idx_business_calendar_tenant_id_desc
    ON batch.business_calendar (tenant_id, id DESC);

CREATE INDEX IF NOT EXISTS idx_batch_window_tenant_id_desc
    ON batch.batch_window (tenant_id, id DESC);

CREATE INDEX IF NOT EXISTS idx_tenant_quota_tenant_id_desc
    ON batch.tenant_quota_policy (tenant_id, id DESC);

-- ---- 自定义排序列的两张表 ----
-- workflow_definition: order by workflow_code, version desc, id desc
CREATE INDEX IF NOT EXISTS idx_workflow_definition_tenant_code_version
    ON batch.workflow_definition (tenant_id, workflow_code, version DESC, id DESC);

-- file_template_config: order by template_code asc, version desc
CREATE INDEX IF NOT EXISTS idx_file_template_tenant_code_version
    ON batch.file_template_config (tenant_id, template_code, version DESC);
