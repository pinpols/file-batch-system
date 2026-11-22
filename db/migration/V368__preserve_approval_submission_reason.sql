-- 提交原因属于来源请求身份；审批或驳回的决定原因可以独立变更。
-- 仅支持空库部署，不推断已决策历史记录的原始提交原因。
ALTER TABLE batch.approval_command ADD COLUMN source_approval_reason VARCHAR(1024);

COMMENT ON COLUMN batch.approval_command.source_approval_reason IS
    '提交审批时冻结的原始原因，用于来源幂等键及恢复回执核对。';
