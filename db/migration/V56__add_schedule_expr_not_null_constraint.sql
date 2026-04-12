-- 修复存量脏数据：CRON / FIXED_RATE 类型但 schedule_expr 为 NULL 的 job_definition
-- 无法自动补全表达式，改为 MANUAL 类型（不参与调度器注册），避免触发器启动崩溃
UPDATE batch.job_definition
SET schedule_type = 'MANUAL',
    trigger_mode  = CASE WHEN trigger_mode = 'SCHEDULED' THEN 'MANUAL' ELSE trigger_mode END
WHERE schedule_type IN ('CRON', 'FIXED_RATE')
  AND schedule_expr IS NULL;

-- 添加 CHECK 约束：CRON / FIXED_RATE 必须有非空 schedule_expr
ALTER TABLE batch.job_definition
    ADD CONSTRAINT ck_job_definition_schedule_expr_required
        CHECK (
            schedule_type NOT IN ('CRON', 'FIXED_RATE')
                OR (schedule_expr IS NOT NULL AND schedule_expr <> '')
            );
