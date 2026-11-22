-- 为运行耗时和迟启动规则保留各自生效时刻，补报不追溯策略生效前已结束的违约。

ALTER TABLE batch.job_monitoring_policy
    ADD COLUMN soft_runtime_updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN start_grace_updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

-- 旧策略至少从最近保存时刻起已采用当前阈值；保留该可证实下界，避免升级时丢失扫描间隙的违约。
UPDATE batch.job_monitoring_policy
SET soft_runtime_updated_at = updated_at,
    start_grace_updated_at = updated_at;

COMMENT ON COLUMN batch.job_monitoring_policy.soft_runtime_updated_at IS
    '运行耗时阈值最近生效时刻；仅阈值改变时刷新，严重级别和其他规则编辑不重置。';
COMMENT ON COLUMN batch.job_monitoring_policy.start_grace_updated_at IS
    '启动宽限阈值最近生效时刻；不补报此前已完成的启动行为。';
