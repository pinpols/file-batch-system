-- =====================================================================
-- V162: task-level startToClose timeout（SDK Phase 4 / ORCH-P4-2）
-- =====================================================================
-- 背景:
--   Phase 4「长任务可控性」。job_definition.timeout_seconds 已有 job-instance
--   级 enforcer（整实例推 FAILED）；本字段补 task 级 startToClose 软取消:
--     - workflow_node.task_timeout_seconds：节点配置源，单个 task 从 RUNNING
--       起算允许跑的最大秒数（startToClose 语义）。NULL / 0 = 无 timeout。
--     - job_task.task_timeout_seconds：派单期从 workflow_node 拷贝的运行态快照，
--       让 TaskTimeoutEnforcer 自洽扫 job_task（无需 4 表 join）。
--   超时后 enforcer 置 job_task.cancel_requested=true（复用 ORCH-P4-1 软取消），
--   SDK 下次 renew 感知到主动停 —— 区别于 job-instance enforcer 的硬 FAILED。
--
-- 改动:
--   - batch.workflow_node 加 task_timeout_seconds INTEGER（可空，无 archive 镜像）
--   - batch.job_task 加 task_timeout_seconds INTEGER（可空）
--   - archive.job_task_archive 同步镜像（archive drift check 强制 1:1）
--
-- 不在范围:
--   - 不回填历史行（旧行 task_timeout_seconds = NULL → enforcer 跳过）
--   - 非 workflow 派发路径不设此值（NULL → 不参与 task 级 timeout）
-- =====================================================================

ALTER TABLE batch.workflow_node
    ADD COLUMN IF NOT EXISTS task_timeout_seconds INTEGER;

ALTER TABLE batch.job_task
    ADD COLUMN IF NOT EXISTS task_timeout_seconds INTEGER;

-- archive 镜像同步（archive schema drift check 强制 1:1 镜像）
ALTER TABLE archive.job_task_archive
    ADD COLUMN IF NOT EXISTS task_timeout_seconds INTEGER;

COMMENT ON COLUMN batch.workflow_node.task_timeout_seconds IS
    'ORCH-P4-2 task 级 startToClose timeout（秒）；NULL/0 = 无 timeout，超时平台主动 cancel';
COMMENT ON COLUMN batch.job_task.task_timeout_seconds IS
    'ORCH-P4-2 派单期拷自 workflow_node.task_timeout_seconds 的运行态快照；TaskTimeoutEnforcer 据此软取消';
