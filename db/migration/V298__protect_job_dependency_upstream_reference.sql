-- 将上游依赖限制在同一租户，并禁止删除仍被引用的作业。

ALTER TABLE batch.job_dependency
    ADD CONSTRAINT fk_job_dependency_upstream
        FOREIGN KEY (tenant_id, upstream_job_code)
        REFERENCES batch.job_definition (tenant_id, job_code)
        ON DELETE NO ACTION NOT VALID;

ALTER TABLE batch.job_dependency
    VALIDATE CONSTRAINT fk_job_dependency_upstream;

COMMENT ON CONSTRAINT fk_job_dependency_upstream ON batch.job_dependency IS
    '租户范围内的上游 Job 定义；NO ACTION 保留有效依赖边，直至显式移除。';
