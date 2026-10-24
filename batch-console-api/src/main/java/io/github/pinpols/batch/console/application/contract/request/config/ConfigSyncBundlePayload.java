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
import java.util.List;
import lombok.Data;

@Data
public class ConfigSyncBundlePayload {

  private List<@Valid JobDefinitionSpec> jobDefinitions;

  private List<@Valid WorkflowDefinitionSpec> workflowDefinitions;

  private List<@Valid FileChannelSpec> fileChannels;

  private List<@Valid FileTemplateSpec> fileTemplates;

  private List<@Valid ResourceQueueSpec> resourceQueues;

  private List<@Valid BatchWindowSpec> batchWindows;

  private List<@Valid BusinessCalendarSpec> businessCalendars;

  private List<@Valid TenantQuotaPolicySpec> quotaPolicies;

  private List<@Valid AlertRoutingSpec> alertRoutings;
}
