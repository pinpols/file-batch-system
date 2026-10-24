-- 为重放操作关联异步 Workflow 根运行时，确保目标请求身份唯一。

CREATE UNIQUE INDEX uq_replay_operation_tenant_target_request
    ON batch.replay_operation (tenant_id, target_request_id)
    WHERE target_request_id IS NOT NULL;

COMMENT ON INDEX batch.uq_replay_operation_tenant_target_request IS
    '每个租户范围的目标请求至多对应一个重放操作；支持异步关联 Workflow 根运行时保持幂等。';
