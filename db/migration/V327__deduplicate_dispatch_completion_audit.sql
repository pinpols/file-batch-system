-- 被重新领取的 DISPATCH 调用可能在接收端
-- 已幂等接收同一请求后，再次执行完成阶段。每个逻辑请求、
-- 文件和通道仅保留一条完成审计，同时保留后续请求及其他通道的记录。

CREATE UNIQUE INDEX IF NOT EXISTS uk_file_audit_dispatch_completion_request
    ON batch.file_audit_log (
        tenant_id,
        file_id,
        evidence_ref,
        (detail_summary ->> 'channelCode')
    )
    WHERE operation_type = 'DISPATCH_COMPLETE'
      AND evidence_ref IS NOT NULL
      AND detail_summary ->> 'channelCode' IS NOT NULL;
