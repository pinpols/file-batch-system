-- WAITING 两种执行模式分别按 ID 有界轮转，避免某一模式无候选时全表扫描。
CREATE INDEX idx_job_partition_waiting_scan_mode
    ON batch.job_partition (partition_status, dry_run, id);
COMMENT ON INDEX batch.idx_job_partition_waiting_scan_mode IS
    '等待分片按状态、演练模式及 ID 范围轮转扫描。';
