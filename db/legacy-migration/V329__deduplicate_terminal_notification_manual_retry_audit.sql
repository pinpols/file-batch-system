-- R32/R30: 原投递人工恢复的持久请求身份;不允许重复操作重新开启发送预算。

CREATE UNIQUE INDEX uq_terminal_notification_retry_audit_request
    ON batch.console_operation_audit (tenant_id, aggregate_id, request_id)
    WHERE aggregate_type = 'NOTIFICATION_DELIVERY' AND action = 'NOTIFICATION_DELIVERY_RETRY';
