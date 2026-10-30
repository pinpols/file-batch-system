-- V324：将热表文件组分区绑定到同租户的来源文件。
-- 冷归档会有意保留 source_file_id 作为历史证据，且不设置热表外键。
-- 此分支部署到全新数据库；在初始化时安装并验证复合引用。

CREATE UNIQUE INDEX uk_file_record_tenant_id
    ON batch.file_record (tenant_id, id);

CREATE INDEX idx_job_partition_source_file_tenant
    ON batch.job_partition (tenant_id, source_file_id);

ALTER TABLE batch.job_partition
    ADD CONSTRAINT fk_job_partition_source_file_tenant
    FOREIGN KEY (tenant_id, source_file_id)
    REFERENCES batch.file_record (tenant_id, id)
    ON DELETE NO ACTION
    NOT VALID;

ALTER TABLE batch.job_partition
    VALIDATE CONSTRAINT fk_job_partition_source_file_tenant;

COMMENT ON CONSTRAINT fk_job_partition_source_file_tenant ON batch.job_partition IS
    '将热态文件包分区绑定到同一租户拥有的来源文件；冷归档仅保留历史弱引用。';
