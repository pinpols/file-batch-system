-- 新库模型：位点属于同租户 Pipeline；先归档并显式删除热位点后才能删除父实例。
-- 冷表保留独立历史身份，不反向引用会被清理的热父表。

CREATE UNIQUE INDEX uk_pipeline_instance_tenant_id
    ON batch.pipeline_instance (tenant_id, id);

ALTER TABLE batch.pipeline_progress
    ADD CONSTRAINT fk_pipeline_progress_tenant_pipeline
    FOREIGN KEY (tenant_id, pipeline_instance_id)
    REFERENCES batch.pipeline_instance (tenant_id, id)
    ON DELETE RESTRICT
    NOT VALID;

ALTER TABLE batch.pipeline_progress
    VALIDATE CONSTRAINT fk_pipeline_progress_tenant_pipeline;
