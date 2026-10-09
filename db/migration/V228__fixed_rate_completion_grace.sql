ALTER TABLE batch.job_monitoring_policy
    ADD COLUMN dependency_completion_window_seconds INTEGER NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_job_monitoring_policy_dependency_completion_window
        CHECK (dependency_completion_window_seconds >= 0);

COMMENT ON COLUMN batch.job_monitoring_policy.dependency_completion_window_seconds IS
    '依赖作业从上游 EFFECTIVE 就绪时刻起允许的最长完成秒数；零表示关闭此规则。';

-- 固定频率作业的墙上时钟截止点无法无损换算成相对每轮触发时刻的时长。
-- 清除不适用的固定频率配置，不臆造宽限时长。
UPDATE batch.job_monitoring_policy mp
SET completion_deadline_local_time = NULL,
    completion_deadline_day_offset = 0,
    completion_deadline_updated_at = CURRENT_TIMESTAMP,
    updated_at = CURRENT_TIMESTAMP
FROM batch.job_definition jd
WHERE jd.tenant_id = mp.tenant_id
  AND jd.id = mp.job_definition_id
  AND jd.schedule_type = 'FIXED_RATE'
  AND (mp.completion_deadline_local_time IS NOT NULL OR mp.completion_deadline_day_offset <> 0);
