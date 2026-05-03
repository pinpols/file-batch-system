-- V93 P0-4: job_instance 补 calendar_code 快照列
--
-- 之前 batch_day_instance ↔ job_instance 用 (tenant_id, calendar_code, biz_date) 隐式联接, job_instance
-- 不存 calendar_code, 要靠 job_definition.calendar_code JOIN 反查. 问题:
--   1. settle scheduler 每 60s 跑 metric 聚合都要 3 表 JOIN (job_instance + job_definition + business_calendar)
--   2. job_definition.calendar_code 改值后, 历史 instance "漂离" 原 batch_day (settle 找不到, catchup 漏算)
--   3. 删除 calendar 不会级联清理 batch_day_instance
--
-- 修法: 创建 instance 时把当时的 calendar_code 抓到运行态 (类似 batch_day_instance.timezone_snapshot 的 snapshot 思路).
-- 配置态变更不污染历史; settle 直读列省一次 JOIN.

ALTER TABLE batch.job_instance
    ADD COLUMN IF NOT EXISTS calendar_code VARCHAR(64);

-- 历史回填: 从 job_definition 反查 (与历史漂移风险并存, 但比 NULL 强; 业务侧约定: V93 之前的历史 instance 不再追溯精度)
UPDATE batch.job_instance i
   SET calendar_code = d.calendar_code
  FROM batch.job_definition d
 WHERE i.tenant_id = d.tenant_id
   AND i.job_code = d.job_code
   AND i.calendar_code IS NULL
   AND d.calendar_code IS NOT NULL;

-- settle 加速索引: settle scheduler 按 (tenant_id, calendar_code, biz_date) 聚合
CREATE INDEX IF NOT EXISTS idx_job_instance_calendar_bizdate
    ON batch.job_instance (tenant_id, calendar_code, biz_date)
 WHERE calendar_code IS NOT NULL;

COMMENT ON COLUMN batch.job_instance.calendar_code IS
    'V93: 创建时从 job_definition.calendar_code 抓快照, 与 batch_day_instance 关联, 不随 config 变更漂移';
