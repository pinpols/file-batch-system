SELECT EXISTS (
    SELECT 1 FROM batch.job_definition WHERE tenant_id = :'tenant_id'
    UNION ALL
    SELECT 1 FROM batch.workflow_definition WHERE tenant_id = :'tenant_id'
    UNION ALL
    SELECT 1 FROM batch.file_channel_config WHERE tenant_id = :'tenant_id'
    UNION ALL
    SELECT 1 FROM batch.file_template_config WHERE tenant_id = :'tenant_id'
);
