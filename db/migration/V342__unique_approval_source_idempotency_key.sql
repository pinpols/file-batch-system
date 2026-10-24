-- 确保同一租户范围内的审批提交重试始终对应同一条持久化审批命令。

CREATE UNIQUE INDEX uk_approval_command_tenant_source_idempotency
    ON batch.approval_command (tenant_id, source_idempotency_key)
    WHERE source_idempotency_key IS NOT NULL;

COMMENT ON INDEX batch.uk_approval_command_tenant_source_idempotency IS
    '每个租户范围的来源幂等键仅对应一条持久化审批命令。';
