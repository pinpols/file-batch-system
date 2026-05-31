-- ADR-035 §2 worker 注册:加 is_self_hosted 列。
--
-- 默认 false → 现有内建 worker(由平台代运维)行为不变。
-- SDK register 时显式带 true → 标识为租户自托管 worker,console "我的 Worker" 页按此过滤。
--
-- 仅新增列,无回填(现有 worker 均为平台内建,false 即正确)。

ALTER TABLE batch.worker_registry
    ADD COLUMN IF NOT EXISTS is_self_hosted BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN batch.worker_registry.is_self_hosted IS
    'ADR-035 自托管标识:true=SDK 注册的租户自托管 worker(batch-worker-sdk);false=平台代部署内建 worker(默认)';

-- 注:worker_registry 不在 archive 范围内(无 worker_registry_archive 表;CLAUDE.md §archive 冷表对齐
-- 针对的是 instance/run/task 这类历史表)。本列无需镜像。
