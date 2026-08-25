-- V195: REPORT 聚合快路径索引
--
-- 普通非 DAG task report 只需要按实例统计 job_partition 状态。该索引覆盖
-- tenant + job_instance 的定位条件和 partition_status 聚合列，避免在高分片实例
-- 上反复扫描无关分区。DAG 节点推进仍使用轻量状态投影，不改变其语义。
CREATE INDEX IF NOT EXISTS idx_job_partition_instance_status
    ON batch.job_partition (tenant_id, job_instance_id, partition_status);
