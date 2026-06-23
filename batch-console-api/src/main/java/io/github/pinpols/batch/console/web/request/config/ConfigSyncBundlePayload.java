package io.github.pinpols.batch.console.web.request.config;

import jakarta.validation.Valid;
import java.util.List;
import lombok.Data;

@Data
public class ConfigSyncBundlePayload {

  @Valid private List<TenantConfigBatchInitRequest.JobDefinitionSpec> jobDefinitions;

  @Valid private List<TenantConfigBatchInitRequest.WorkflowDefinitionSpec> workflowDefinitions;

  @Valid private List<TenantConfigBatchInitRequest.PipelineDefinitionSpec> pipelineDefinitions;

  @Valid private List<TenantConfigBatchInitRequest.FileChannelSpec> fileChannels;

  @Valid private List<TenantConfigBatchInitRequest.FileTemplateSpec> fileTemplates;

  @Valid private List<TenantConfigBatchInitRequest.ResourceQueueSpec> resourceQueues;

  @Valid private List<TenantConfigBatchInitRequest.BatchWindowSpec> batchWindows;

  @Valid private List<TenantConfigBatchInitRequest.BusinessCalendarSpec> businessCalendars;

  @Valid private List<TenantConfigBatchInitRequest.TenantQuotaPolicySpec> quotaPolicies;

  @Valid private List<TenantConfigBatchInitRequest.AlertRoutingSpec> alertRoutings;
}
