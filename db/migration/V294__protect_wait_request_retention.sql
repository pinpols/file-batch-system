-- 等待记录账本与原始请求采用相同的保留期限。显式终态清理必须
-- 先于请求删除；删除父记录不得悄悄移除待恢复的证据。

ALTER TABLE batch.job_dependency_waiting_launch
    ADD CONSTRAINT fk_job_dependency_wait_request
        FOREIGN KEY (tenant_id, request_id)
        REFERENCES batch.trigger_request (tenant_id, request_id) NOT VALID;
ALTER TABLE batch.job_dependency_waiting_launch
    VALIDATE CONSTRAINT fk_job_dependency_wait_request;

COMMENT ON CONSTRAINT fk_job_dependency_wait_request ON batch.job_dependency_waiting_launch IS
    '租户范围内的请求身份；NO ACTION 可保留等待中及终态证据，直至执行显式保留期清理。';
COMMENT ON CONSTRAINT fk_workflow_launch_wait_request_retention ON batch.workflow_launch_wait IS
    '以 NO ACTION 替代级联删除；清理原请求前必须先清理终态等待记录。';
