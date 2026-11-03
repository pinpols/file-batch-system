-- 用途：为 TB 覆盖 CATCH_UP 场景准备已截止业务日和拒绝 occurrence，不用 SQL 伪造补跑成功。
-- 用法：由 coverage-harness 在本地测试库显式调用；仅允许 tb_cov_calendar，完成后由覆盖清理删除。
INSERT INTO batch.batch_day_instance (tenant_id, calendar_code, biz_date, day_status, open_at, cutoff_at)
VALUES ('tb', 'tb_cov_calendar', DATE '2026-11-03', 'CUTOFF', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, calendar_code, biz_date) DO UPDATE
SET day_status = 'CUTOFF', cutoff_at = CURRENT_TIMESTAMP, settled_at = NULL;

INSERT INTO batch.scheduled_occurrence (
  tenant_id, workflow_definition_id, workflow_code, schedule_version, calendar_code,
  scheduled_fire_time, biz_date, occurrence_status, last_rejection_reason, created_at, updated_at)
SELECT 'tb', wd.id, wd.workflow_code, wd.version, 'tb_cov_calendar', CURRENT_TIMESTAMP, DATE '2026-11-03',
       'CLOSED_DAY_REJECTED', 'coverage harness CUTOFF', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM batch.workflow_definition wd
JOIN batch.job_definition jd ON jd.id = wd.single_job_definition_id
WHERE jd.tenant_id = 'tb' AND jd.job_code = 'TB_COV_ATOMIC'
ORDER BY wd.version DESC
LIMIT 1
ON CONFLICT (tenant_id, workflow_definition_id, scheduled_fire_time) DO UPDATE
SET occurrence_status = 'CLOSED_DAY_REJECTED', request_id = NULL,
    last_rejection_reason = 'coverage harness CUTOFF', updated_at = CURRENT_TIMESTAMP;
