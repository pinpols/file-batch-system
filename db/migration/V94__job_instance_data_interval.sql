-- V94: job_instance.data_interval_start / data_interval_end (Airflow 风格半开区间)
--
-- 之前 bizDate (LocalDate) 单点 = "今天的数据", 没法表达 "2026-05-03 14:30:00 - 14:35:00 这 5 分钟" 的场景.
-- 加 data_interval_start/end TIMESTAMPTZ 后, IMPORT/EXPORT worker 可拼 SQL 真正按分钟级切片处理.
--
-- 触发侧计算:
--   CRON / FIXED_RATE: [thisFireAt, nextFireAt)
--   API / MANUAL:      调用方提供, 否则 null
--   fallback:          [bizDate.atStartOfDay(zone), bizDate+1.atStartOfDay(zone))  (worker 端兜底)
--
-- 两个字段允许 null: 向后兼容 V94 之前的 instance, 也兼容不需要时间窗口的简单作业.

ALTER TABLE batch.job_instance
    ADD COLUMN IF NOT EXISTS data_interval_start TIMESTAMPTZ;

ALTER TABLE batch.job_instance
    ADD COLUMN IF NOT EXISTS data_interval_end TIMESTAMPTZ;

-- 软约束 (允许双 null, 但不能只一边非 null): start 和 end 必须同时存在或同时不存在
ALTER TABLE batch.job_instance
    DROP CONSTRAINT IF EXISTS ck_job_instance_data_interval_pair;

ALTER TABLE batch.job_instance
    ADD CONSTRAINT ck_job_instance_data_interval_pair
    CHECK ((data_interval_start IS NULL AND data_interval_end IS NULL)
        OR (data_interval_start IS NOT NULL AND data_interval_end IS NOT NULL
            AND data_interval_start < data_interval_end));

COMMENT ON COLUMN batch.job_instance.data_interval_start IS
    'V94: Airflow 风格半开区间起点; 业务可拼 WHERE update_time >= :start';
COMMENT ON COLUMN batch.job_instance.data_interval_end IS
    'V94: Airflow 风格半开区间终点; 业务可拼 WHERE update_time < :end';
