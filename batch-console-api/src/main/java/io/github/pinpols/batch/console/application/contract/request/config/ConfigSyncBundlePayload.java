package io.github.pinpols.batch.console.application.contract.request.config;

import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.AlertRoutingSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.BatchWindowSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.BusinessCalendarSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.FileChannelSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.FileTemplateSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.JobDefinitionSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.ResourceQueueSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.TenantQuotaPolicySpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.WorkflowDefinitionSpec;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.Data;

@Data
public class ConfigSyncBundlePayload {

  private List<@NotNull @Valid JobDefinitionSpec> jobDefinitions;

  private List<@NotNull @Valid WorkflowDefinitionSpec> workflowDefinitions;

  private List<@NotNull @Valid FileChannelSpec> fileChannels;

  private List<@NotNull @Valid FileTemplateSpec> fileTemplates;

  private List<@NotNull @Valid ResourceQueueSpec> resourceQueues;

  private List<@NotNull @Valid BatchWindowSpec> batchWindows;

  private List<@NotNull @Valid BusinessCalendarSpec> businessCalendars;

  private List<@NotNull @Valid TenantQuotaPolicySpec> quotaPolicies;

  private List<@NotNull @Valid AlertRoutingSpec> alertRoutings;
}
