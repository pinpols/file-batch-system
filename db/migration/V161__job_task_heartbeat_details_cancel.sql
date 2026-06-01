-- =====================================================================
-- V161: job_task heartbeat details + cancel push（SDK Phase 4 / ORCH-P4-1）
-- =====================================================================
-- 背景:
--   Phase 4「长任务可控性」。5000 万行 import 跑 30 分钟不再是黑盒:
--     - heartbeat_details：worker 经 POST /internal/tasks/{id}/renew 携带的
--       任意进度 / checkpoint 快照（Temporal heartbeat-with-details 等价），
--       供 console「任务详情」页 SELECT 最新进度。
--     - heartbeat_at：最近一次带 details 的心跳时间，判活 / 进度新鲜度用。
--     - cancel_requested：平台侧（运维 POST /internal/tasks/{id}/cancel 或
--       ORCH-P4-2 超时逻辑）置 true；renew response 回带此标记，SDK 下次心跳
--       即感知到取消请求，主动停（不等 lease 60s 超时）。
--
-- 改动:
--   - batch.job_task 加 heartbeat_details JSONB（可空）、heartbeat_at
--     TIMESTAMPTZ（可空）、cancel_requested BOOLEAN NOT NULL DEFAULT false
--   - archive.job_task_archive 同步镜像（archive drift check 强制 1:1）
--
-- 不在范围:
--   - 不回填历史 task（旧行 heartbeat_details/at = NULL，cancel = false）
--   - 不放敏感凭据（details 仅进度 / checkpoint，禁写密钥，roadmap §5.5）
-- =====================================================================

ALTER TABLE batch.job_task
    ADD COLUMN IF NOT EXISTS heartbeat_details JSONB,
    ADD COLUMN IF NOT EXISTS heartbeat_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS cancel_requested BOOLEAN NOT NULL DEFAULT false;

-- archive 镜像同步（archive schema drift check 强制 1:1 镜像）
ALTER TABLE archive.job_task_archive
    ADD COLUMN IF NOT EXISTS heartbeat_details JSONB,
    ADD COLUMN IF NOT EXISTS heartbeat_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS cancel_requested BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN batch.job_task.heartbeat_details IS
    'ORCH-P4-1 worker 经 renew 携带的进度 / checkpoint 快照（JSON）；console 任务详情读取；敏感凭据禁入';
COMMENT ON COLUMN batch.job_task.heartbeat_at IS
    'ORCH-P4-1 最近一次带 details 的心跳时间（UTC）';
COMMENT ON COLUMN batch.job_task.cancel_requested IS
    'ORCH-P4-1 平台请求取消标记；renew response 回带，SDK 主动停（不等 lease 超时）';
