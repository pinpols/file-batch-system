-- flyway:executeInTransaction=false
-- ADR-010 启动对账器只扫描尚未完成 T2 回写的历史 ACCEPTED 请求。启动积压时该集合
-- 可能较大，通过部分索引避免分钟级恢复任务反复扫描 trigger_request 堆表。
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_trigger_request_stale_accepted
    ON batch.trigger_request (created_at, id)
    INCLUDE (tenant_id, request_id)
    WHERE request_status = 'ACCEPTED'
      AND related_job_instance_id IS NULL;
