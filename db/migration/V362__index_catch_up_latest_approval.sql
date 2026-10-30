-- 补跑列表先分页请求，再按租户与目标查找最新审批，避免排序整个租户的审批集合。
CREATE INDEX IF NOT EXISTS idx_approval_catch_up_latest
    ON batch.approval_command (tenant_id, target_id, id DESC)
    WHERE approval_type = 'CATCH_UP';

COMMENT ON INDEX batch.idx_approval_catch_up_latest IS
    '补跑请求按租户及目标查询最新审批单的局部索引。';
