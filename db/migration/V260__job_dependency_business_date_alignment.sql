-- 按依赖边配置作业业务日期对齐方式。现有关系默认仍使用同一日期。

ALTER TABLE batch.job_dependency
    ADD COLUMN biz_date_alignment VARCHAR(32) NOT NULL DEFAULT 'SAME_DAY';

ALTER TABLE batch.job_dependency
    ADD CONSTRAINT ck_job_dependency_biz_date_alignment
    CHECK (biz_date_alignment IN ('SAME_DAY', 'PREVIOUS_BIZ_DAY')) NOT VALID;

ALTER TABLE batch.job_dependency
    VALIDATE CONSTRAINT ck_job_dependency_biz_date_alignment;

COMMENT ON COLUMN batch.job_dependency.biz_date_alignment IS
    '上游结果业务日对齐策略；PREVIOUS_BIZ_DAY 使用现有周一至周五算术，不感知节假日';
