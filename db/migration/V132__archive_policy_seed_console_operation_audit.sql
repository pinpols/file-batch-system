-- 给所有 ACTIVE 租户 INSERT 一行 console_operation_audit 归档策略(declarative 注册)。
--
-- 默认配置:
--   retention_days = 180        (审计场景常见 6 个月)
--   archive_enabled = FALSE     (暂无 archive.console_operation_audit_archive 影子表)
--   cleanup_enabled = FALSE     (运维确认后再开,避免误删)
--
-- 同步部署到新环境时,这条 migration 保证 archive_policy 默认行存在,
-- 运维通过 console UI 调整 retention_days / 启用 cleanup 即可,不用手动 INSERT。
--
-- 已存在的不动(WHERE NOT EXISTS 兜底,可重复执行)。
INSERT INTO batch.archive_policy (
    tenant_id, target_table, retention_days,
    archive_enabled, cleanup_enabled, batch_size,
    description, created_by, updated_by
)
SELECT
    t.tenant_id,
    'console_operation_audit',
    180,
    FALSE,
    FALSE,
    1000,
    '通用控制台操作审计(@AuditAction 落库)。enable cleanup_enabled 才会自动删除超期数据',
    'system',
    'system'
FROM batch.tenant t
WHERE t.status = 'ACTIVE'
  AND NOT EXISTS (
      SELECT 1 FROM batch.archive_policy p
      WHERE p.tenant_id = t.tenant_id
        AND p.target_table = 'console_operation_audit'
  );
