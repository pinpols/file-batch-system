package com.example.batch.console.web.request;

import jakarta.validation.Valid;
import java.util.List;
import lombok.Data;

@Data
public class ConfigSyncBundlePayload {

    @Valid
    private List<TenantConfigBatchInitRequest.JobDefinitionSpec> jobDefinitions;

    @Valid
    private List<TenantConfigBatchInitRequest.WorkflowDefinitionSpec> workflowDefinitions;

    @Valid
    private List<TenantConfigBatchInitRequest.PipelineDefinitionSpec> pipelineDefinitions;

    @Valid
    private List<TenantConfigBatchInitRequest.FileChannelSpec> fileChannels;

    @Valid
    private List<TenantConfigBatchInitRequest.FileTemplateSpec> fileTemplates;
}
