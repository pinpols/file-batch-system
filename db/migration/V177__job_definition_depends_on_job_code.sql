-- =========================================================
-- V177 - 触发器依赖感知 fire 的上游声明(ADR-043 Phase B)
-- Notes:
-- 1) job_definition 加可选 depends_on_job_code:声明本触发器 fire 前需就绪的上游 job。
--    非空时,scheduled fire 在 launch 前查上游同 bizDate 是否已 SUCCESS,未就绪则跳过本次
--    (下个调度点重试),不盲 fire 注定无输入/半输入的批。
-- 2) job_definition 是配置表,无 archive.* 镜像(已核对),故无归档迁移配对。
-- 3) 向后兼容:默认 NULL = 无依赖,所有存量触发器行为不变。
-- =========================================================

ALTER TABLE batch.job_definition
    ADD COLUMN IF NOT EXISTS depends_on_job_code VARCHAR(64);

COMMENT ON COLUMN batch.job_definition.depends_on_job_code IS
    'ADR-043: 上游 job code;非空时本触发器 fire 前要求该 job 同 bizDate 已 SUCCESS,否则跳过本次';
