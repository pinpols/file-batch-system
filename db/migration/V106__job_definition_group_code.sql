-- =====================================================================
-- V106: job_definition.job_group_code (SAME_JOB_GROUP 前日依赖支撑)
-- =====================================================================
-- 背景:
--   §14.3 P1 — previous_day_dependency_scope 的 SAME_JOB / SAME_JOB_GROUP
--   细粒度差异需要分组概念。SAME_JOB 只看自己上一日, SAME_JOB_GROUP 看
--   同组(如同一业务域的对账 + 出账 + 推送)上一日是否全部完结。
--
-- 字段语义:
--   job_group_code 可为 NULL — 此时 SAME_JOB_GROUP 退化为 SAME_JOB 语义
--   (只检查自身)。运维需主动配组才能启用跨 job 等待。
--
-- 索引:
--   idx_job_definition_tenant_group — 给 BatchDayGateService 按
--   (tenant_id, job_group_code) 反查"同组所有 job_code"。
-- =====================================================================

ALTER TABLE batch.job_definition
    ADD COLUMN IF NOT EXISTS job_group_code VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_job_definition_tenant_group
    ON batch.job_definition (tenant_id, job_group_code)
 WHERE job_group_code IS NOT NULL;

COMMENT ON COLUMN batch.job_definition.job_group_code IS
    '业务分组码 — SAME_JOB_GROUP 前日门闩用; NULL 时该 scope 退化为 SAME_JOB 语义';
