-- =========================================================
-- V136: job_instance 增强 CHECK — 防"已创建但无触发来源"脏状态 (NOT VALID 阶段)
--
-- 依据: docs/analysis/dba-schema-review-2026-05-20.md §3.6 / Quick wins §8
--
-- 背景:
--   V5 创建 batch.job_instance 时 trigger_request_id 可空。预期语义:
--     - SCHEDULED / API / EVENT / CATCH_UP: 必有 trigger_request_id (Trigger 侧落库)
--     - MANUAL: 控制台直接拉起,允许无 trigger_request_id
--   但表层无任何约束,可能写出"trigger_type=SCHEDULED 且 trigger_request_id IS NULL"
--   这类逻辑死状态(通常意味着 Trigger → Orchestrator 链路被旁路或脏数据导入)。
--
-- 设计:
--   - NOT VALID 引入,只约束新写入,旧数据不强制扫描;
--   - V137 配套 VALIDATE,启动期 NotValidConstraintGuard 兜底,
--     防止漏 VALIDATE 留下"逻辑生效但旧数据未校验"窗口。
--
-- 同 archive 镜像同步: archive.job_instance_archive 也加同名 NOT VALID 约束,
-- 避免 ArchiveSchemaDriftCheck 比对差异。
-- =========================================================

ALTER TABLE batch.job_instance
    ADD CONSTRAINT ck_job_instance_trigger_source
    CHECK (trigger_request_id IS NOT NULL OR trigger_type = 'MANUAL')
    NOT VALID;

ALTER TABLE archive.job_instance_archive
    ADD CONSTRAINT ck_job_instance_archive_trigger_source
    CHECK (trigger_request_id IS NOT NULL OR trigger_type = 'MANUAL')
    NOT VALID;
