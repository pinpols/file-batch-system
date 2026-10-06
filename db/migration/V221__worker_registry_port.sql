-- Worker 自报实际监听端口，供运维排障（健康检查 / 指标端点定位）与控制面展示。
-- 内置 worker 由 AbstractWorkerLoop#resolveWorkerPort 上报 Spring 运行时绑定端口
-- （local.server.port 优先，server.port 兜底）；SDK 自托管 worker 可上报自己的监听端口。
-- 可空不设默认：老 worker / 老 SDK / 非 web 上下文不上报，保持 NULL，不破坏既有注册链路
-- （register 与 heartbeat 都走 WorkerHeartbeatDto，缺失即 null，UPDATE 走 coalesce 不会抹掉已有值）。
-- worker_registry 是运行态注册表，不在 ArchiveSchemaDriftCheck.ARCHIVED_TABLES 清单内，无 archive 镜像。
ALTER TABLE batch.worker_registry
    ADD COLUMN IF NOT EXISTS port INTEGER;

COMMENT ON COLUMN batch.worker_registry.port IS
    'Worker 实际监听 HTTP 端口；NULL=未上报（老 worker / 老 SDK / 非 web 上下文或端口未绑定）';
