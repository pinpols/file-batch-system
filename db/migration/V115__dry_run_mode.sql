-- =====================================================================
-- V115: Dry-run 一等字段（ADR-026 Stage 1）
-- =====================================================================
-- 背景:
--   §14.3.2 设计层缺口"dry-run 模式"。当前没有"安全演练"通道，要看"凌晨
--   日终批会跑成什么样"只能让它真跑，副作用直接落业务表 + 发外部清算。
--
-- 决策（ADR-026 §决策）:
--   dry_run 一等字段贯穿 launch → instance → workflow_run → batch_day_
--   instance；与 bypass-mode 正交（bypass=放行不安全，dry-run=安全但不
--   副作用）。
--
-- 字段:
--   job_instance.dry_run         BOOLEAN NOT NULL DEFAULT false
--   workflow_run.dry_run         BOOLEAN NOT NULL DEFAULT false
--   batch_day_instance.dry_run   BOOLEAN NOT NULL DEFAULT false
--
-- 兼容:
--   老行 dry_run=false（与现行为一致）；新 LaunchRequest 默认不带 dryRun
--   时也是 false。
--
-- 不在本 migration 范围（后续 stages）:
--   终态 SUCCESS_DRY_RUN / FAILED_DRY_RUN — 涉及 ck_instance_status 改
--   动 + 状态机 wide blast radius，留 V116+；当前 dry_run instance 仍
--   走 SUCCESS / FAILED 终态，但 result_version 端做 status=DRY_RUN 隔离
--   防止下游误读。
-- =====================================================================

ALTER TABLE batch.job_instance
    ADD COLUMN IF NOT EXISTS dry_run BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE archive.job_instance_archive
    ADD COLUMN IF NOT EXISTS dry_run BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE batch.workflow_run
    ADD COLUMN IF NOT EXISTS dry_run BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE archive.workflow_run_archive
    ADD COLUMN IF NOT EXISTS dry_run BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE batch.batch_day_instance
    ADD COLUMN IF NOT EXISTS dry_run BOOLEAN NOT NULL DEFAULT false;
-- 注：batch_day_instance 不在 V71 14 张归档对照表内，无需 archive 镜像。

COMMENT ON COLUMN batch.job_instance.dry_run IS
    'ADR-026 dry-run 演练标记；true = 不副作用（不写业务表 / 不发外部 IO / 不进 EFFECTIVE 链）';
COMMENT ON COLUMN batch.workflow_run.dry_run IS
    'ADR-026 dry-run 演练标记；与 batch_day_instance.dry_run / job_instance.dry_run 必须一致';
COMMENT ON COLUMN batch.batch_day_instance.dry_run IS
    'ADR-026 dry-run 整日演练标记';

-- result_version 用既有 status 列扩展，加 DRY_RUN 状态值（不创建 EFFECTIVE 单一约束）
ALTER TABLE batch.result_version DROP CONSTRAINT IF EXISTS ck_result_version_status;
ALTER TABLE batch.result_version ADD CONSTRAINT ck_result_version_status
    CHECK (status IN ('PENDING', 'EFFECTIVE', 'SUPERSEDED', 'ARCHIVED', 'DRY_RUN'));

ALTER TABLE archive.result_version_archive DROP CONSTRAINT IF EXISTS ck_result_version_status;
-- archive 镜像跟随放宽（避免归档失败）；archive 不强制 status check 也可
