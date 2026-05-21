-- 清理孤儿 'default-tenant':
--   V42 曾种入 tenant_id='default-tenant' 的演示账号 → V50 自动回填一行同名 tenant →
--   V51 删除账号但 tenant 行残留,导致租户切换菜单始终多出一项无意义条目。
--
-- 删除条件:同时满足才删,任何残留关联视为「用户还在用」直接跳过(防误删)。
--   1. 无 console_user_account
--   2. 无 job_definition / pipeline_definition / workflow_definition
--   3. 无 job_instance / workflow_run / pipeline_instance
--
-- 这是「数据修正」非「业务迁移」;不需要 profile guard:
--   - 命中删除条件 = 用户从没用过,删除安全
--   - 不命中 = 业务在用,保留
DELETE FROM batch.tenant t
WHERE t.tenant_id = 'default-tenant'
  AND NOT EXISTS (SELECT 1 FROM batch.console_user_account WHERE tenant_id = t.tenant_id)
  AND NOT EXISTS (SELECT 1 FROM batch.job_definition       WHERE tenant_id = t.tenant_id)
  AND NOT EXISTS (SELECT 1 FROM batch.pipeline_definition  WHERE tenant_id = t.tenant_id)
  AND NOT EXISTS (SELECT 1 FROM batch.workflow_definition  WHERE tenant_id = t.tenant_id)
  AND NOT EXISTS (SELECT 1 FROM batch.job_instance         WHERE tenant_id = t.tenant_id)
  AND NOT EXISTS (SELECT 1 FROM batch.workflow_run         WHERE tenant_id = t.tenant_id)
  AND NOT EXISTS (SELECT 1 FROM batch.pipeline_instance    WHERE tenant_id = t.tenant_id);
