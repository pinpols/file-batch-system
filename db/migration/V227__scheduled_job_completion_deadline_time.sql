-- 为作业完成时限增加本地时间和跨业务日偏移配置。

ALTER TABLE batch.job_monitoring_policy
    ADD COLUMN completion_deadline_local_time TIME WITHOUT TIME ZONE,
    ADD COLUMN completion_deadline_day_offset SMALLINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_job_monitoring_policy_completion_day_offset
        CHECK (completion_deadline_day_offset BETWEEN 0 AND 1) NOT VALID;

ALTER TABLE batch.job_monitoring_policy
    VALIDATE CONSTRAINT ck_job_monitoring_policy_completion_day_offset;

COMMENT ON COLUMN batch.job_monitoring_policy.completion_deadline_local_time IS
    '定时作业的本地完成截止时刻，按 job_definition.timezone 解释；NULL 表示关闭此定时作业告警。';
COMMENT ON COLUMN batch.job_monitoring_policy.completion_deadline_day_offset IS
    '本地完成截止日期相对计划触发日的日历日偏移：0 表示当天，1 表示次日。';
COMMENT ON COLUMN batch.job_monitoring_policy.completion_deadline_seconds IS
    '为兼容旧配置而保留的已弃用字段；定时作业未按时完成告警已改用 completion_deadline_local_time 和 completion_deadline_day_offset。现有秒数值不会自动转换。';
