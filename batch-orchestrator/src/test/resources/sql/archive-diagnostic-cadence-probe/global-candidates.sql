-- 实际归档入口是全局选择，发现其他旧夹具即拒绝，不清理或过滤掉它们。
select count(*) from batch.job_instance
where instance_status in (
    'SUCCESS', 'PARTIAL_FAILED', 'FAILED', 'CANCELLED', 'TERMINATED', 'SUCCESS_DRY_RUN', 'FAILED_DRY_RUN'
) and finished_at is not null and finished_at < :cutoff
