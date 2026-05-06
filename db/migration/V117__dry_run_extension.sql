-- =====================================================================
-- V117: Dry-run 扩展（ADR-026 §三层粒度配套 / partition + task 染色 + 终态扩展）
-- =====================================================================
-- 背景:
--   V115 给 job_instance / workflow_run / batch_day_instance 加了 dry_run，
--   priority-scope §ADR-026 §决策 "dry_run 一等字段贯穿 LaunchRequest →
--   instance / partition / task" 还差 partition + task；同时 §决策"终态
--   SUCCESS_DRY_RUN / FAILED_DRY_RUN 隔离"也未落地。
--
-- 改动:
--   - batch.job_partition / batch.job_task + archive 镜像加 dry_run BOOL
--   - batch.trigger_request 加 dry_run BOOL（从 trigger 入口透传）
--   - ck_job_instance_status 扩 SUCCESS_DRY_RUN / FAILED_DRY_RUN
--   - ck_workflow_run_status 扩 SUCCESS_DRY_RUN / FAILED_DRY_RUN
--
-- 不在范围（边界红线，按 priority-scope §5 不做）:
--   - FULL_SIMULATION 模式（事务回滚 / Kafka 不消费 / 真写后删）
--   - mixed mode（同 instance 内一部分 dry 一部分 real）
--   - bypass-mode 复用（bypass=放行不安全；dry-run=安全但不副作用）
-- =====================================================================

-- partition / task 染色
ALTER TABLE batch.job_partition
    ADD COLUMN IF NOT EXISTS dry_run BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE batch.job_task
    ADD COLUMN IF NOT EXISTS dry_run BOOLEAN NOT NULL DEFAULT false;

-- archive 镜像同步（archive schema drift check 强制 1:1 镜像）
ALTER TABLE archive.job_partition_archive
    ADD COLUMN IF NOT EXISTS dry_run BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE archive.job_task_archive
    ADD COLUMN IF NOT EXISTS dry_run BOOLEAN NOT NULL DEFAULT false;

-- trigger_request 入口染色
ALTER TABLE batch.trigger_request
    ADD COLUMN IF NOT EXISTS dry_run BOOLEAN NOT NULL DEFAULT false;

-- 终态扩展：job_instance
ALTER TABLE batch.job_instance DROP CONSTRAINT IF EXISTS ck_job_instance_status;
ALTER TABLE batch.job_instance ADD CONSTRAINT ck_job_instance_status
    CHECK (instance_status IN (
        'CREATED', 'WAITING', 'READY', 'RUNNING',
        'PARTIAL_FAILED', 'SUCCESS', 'FAILED', 'CANCELLED', 'TERMINATED',
        'SUCCESS_DRY_RUN', 'FAILED_DRY_RUN'
    ));

-- 终态扩展：workflow_run
ALTER TABLE batch.workflow_run DROP CONSTRAINT IF EXISTS ck_workflow_run_status;
ALTER TABLE batch.workflow_run ADD CONSTRAINT ck_workflow_run_status
    CHECK (run_status IN (
        'CREATED', 'RUNNING', 'SUCCESS', 'FAILED', 'TERMINATED',
        'SUCCESS_DRY_RUN', 'FAILED_DRY_RUN'
    ));

COMMENT ON COLUMN batch.job_partition.dry_run IS
    'ADR-026 dry-run 演练；从父 instance.dry_run 透传，与父值一致';
COMMENT ON COLUMN batch.job_task.dry_run IS
    'ADR-026 dry-run 演练；从父 partition.dry_run 透传，与父值一致';
COMMENT ON COLUMN batch.trigger_request.dry_run IS
    'ADR-026 dry-run 演练；trigger 入口标记，落到 LaunchRequest.dryRun → 后续 instance/partition/task 链';
