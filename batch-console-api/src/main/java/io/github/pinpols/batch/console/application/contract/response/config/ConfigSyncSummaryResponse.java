package io.github.pinpols.batch.console.application.contract.response.config;

import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncBundlePayload;
import java.util.List;

/** 配置同步包内各类配置的数量汇总。 */
public record ConfigSyncSummaryResponse(
    int jobDefinitions,
    int workflowDefinitions,
    int fileChannels,
    int fileTemplates,
    int resourceQueues,
    int batchWindows,
    int businessCalendars,
    int quotaPolicies,
    int alertRoutings) {

  public static ConfigSyncSummaryResponse from(ConfigSyncBundlePayload bundle) {
    int jobDefinitionCount = sizeOf(bundle.getJobDefinitions());
    int workflowDefinitionCount = sizeOf(bundle.getWorkflowDefinitions());
    int fileChannelCount = sizeOf(bundle.getFileChannels());
    int fileTemplateCount = sizeOf(bundle.getFileTemplates());
    int resourceQueueCount = sizeOf(bundle.getResourceQueues());
    int batchWindowCount = sizeOf(bundle.getBatchWindows());
    int businessCalendarCount = sizeOf(bundle.getBusinessCalendars());
    int quotaPolicyCount = sizeOf(bundle.getQuotaPolicies());
    int alertRoutingCount = sizeOf(bundle.getAlertRoutings());
    ConfigSyncSummaryResponse summary = new ConfigSyncSummaryResponse(
        jobDefinitionCount,
        workflowDefinitionCount,
        fileChannelCount,
        fileTemplateCount,
        resourceQueueCount,
        batchWindowCount,
        businessCalendarCount,
        quotaPolicyCount,
        alertRoutingCount);
    return summary;
  }

  public int total() {
    return jobDefinitions
        + workflowDefinitions
        + fileChannels
        + fileTemplates
        + resourceQueues
        + batchWindows
        + businessCalendars
        + quotaPolicies
        + alertRoutings;
  }

  private static int sizeOf(List<?> values) {
    return values == null ? 0 : values.size();
  }
}
