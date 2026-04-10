-- uk_trigger_request_tenant_dedup (tenant_id, dedup_key) 过于严格：同一个业务幂等 key 可能
-- 对应多个不同 request_id 的重试请求（调用方重试时 requestId 不同但 dedupKey 相同）。
-- 去重防重应当在 job_instance 层（uk_job_instance_tenant_dedup）执行，trigger_request
-- 只需保证 (tenant_id, request_id) 唯一即可。
ALTER TABLE batch.trigger_request
    DROP CONSTRAINT IF EXISTS uk_trigger_request_tenant_dedup;
