-- =====================================================================
-- V163: worker_registry build/sdk 指纹列（SDK Phase 5 / SDK-P5-3）
-- =====================================================================
-- 背景:
--   自托管 SDK worker 上报「运行指纹」便于运维定位具体进程实例:
--     - host_name / host_ip / process_id 在 V3 建表时已存在但从未写入;
--       本期把 register 路径接上（SDK 上报 → orchestrator 落库）。
--     - build_id：租户应用构建标识（CI 产出，经 BatchPlatformClientConfig
--       注入），用于区分同一 SDK 版本下不同业务构建。
--     - sdk_version：worker 链接的 batch-worker-sdk 库版本（从 SDK jar
--       manifest Implementation-Version 自动探测）。
--
-- 改动:
--   - batch.worker_registry 加 build_id VARCHAR(128)、sdk_version VARCHAR(64)
--     （均可空：非 SDK 的文件 pipeline worker 不上报）。
--
-- 不在范围:
--   - worker_registry 不在 archive 冷表镜像清单（ArchiveSchemaDriftCheck
--     ARCHIVED_TABLES 不含），无需补 archive migration。
--   - 不回填历史行（旧行 build_id / sdk_version = NULL）。
-- =====================================================================

ALTER TABLE batch.worker_registry
    ADD COLUMN IF NOT EXISTS build_id    VARCHAR(128),
    ADD COLUMN IF NOT EXISTS sdk_version VARCHAR(64);
