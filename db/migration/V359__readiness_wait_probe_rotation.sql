-- flyway:executeInTransaction=false
-- 等待探测按最近检查时间轮转; 保留单轮上限, 避免长期未就绪项饿死后续记录。
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_readiness_wait_probe_rotation
    ON batch.readiness_wait (updated_at, deadline, id)
    WHERE wait_status = 'PENDING';
