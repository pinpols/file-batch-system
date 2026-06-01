-- =====================================================================
-- V160: job_task.effective_parameters（SDK Phase 3 M3.1 / ORCH-P3-3）
-- =====================================================================
-- 背景:
--   ORCH-P3-2b 让派单期 LaunchParamResolver 把
--   descriptor.defaults < job_definition.default_params < request.params
--   合并成「生效参数」。但 task 行只存 task_payload（投递给 worker 的 wire
--   载荷，含 batchNo/bizDate 等运行态注入），缺一份「这次派单到底用了哪些
--   参数」的审计快照。effective_parameters 落这份合并结果，供 console
--   排障 / dry-run 复盘读取，与 task_payload 解耦。
--
-- 改动:
--   - batch.job_task 加 effective_parameters JSONB（可空，旧行 NULL）
--   - archive.job_task_archive 同步镜像（archive drift check 强制 1:1）
--
-- 不在范围:
--   - 不回填历史 task（旧行保持 NULL，读侧兜底空对象）
--   - 不放敏感凭据（DB 密码 / OAuth secret 走环境变量，roadmap §5.5）
-- =====================================================================

ALTER TABLE batch.job_task
    ADD COLUMN IF NOT EXISTS effective_parameters JSONB;

-- archive 镜像同步（archive schema drift check 强制 1:1 镜像）
ALTER TABLE archive.job_task_archive
    ADD COLUMN IF NOT EXISTS effective_parameters JSONB;

COMMENT ON COLUMN batch.job_task.effective_parameters IS
    'ORCH-P3-3 派单期生效参数快照（descriptor.defaults < default_params < request.params 合并后）；审计/排障用，与 task_payload 解耦；敏感凭据禁入';
