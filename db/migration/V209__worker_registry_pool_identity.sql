-- 将稳定的 Kafka 路由池与具体 Worker 进程/Pod 身份分开。
-- 既有 Worker 和外部 SDK Worker 回填 worker_code，继续保持单实例池语义。
-- worker_registry 是运行态注册表，不在 ArchiveSchemaDriftCheck.ARCHIVED_TABLES 清单内，无 archive 镜像。
ALTER TABLE batch.worker_registry
    ADD COLUMN IF NOT EXISTS worker_pool_code VARCHAR(128);

UPDATE batch.worker_registry
   SET worker_pool_code = worker_code
 WHERE worker_pool_code IS NULL;

CREATE INDEX IF NOT EXISTS idx_worker_registry_pool_status_load
    ON batch.worker_registry (tenant_id, worker_pool_code, status, current_load, heartbeat_at DESC);

COMMENT ON COLUMN batch.worker_registry.worker_pool_code IS
    '稳定的 Kafka 路由池代码；worker_code 标识一个具体运行实例';
