package io.github.pinpols.batch.console.domain.job.application.contract.response;

import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncBundlePayload;
import java.util.List;

/** Job Bundle 内各类可移植配置的数量汇总。 */
public record ConsoleJobBundleSummaryResponse(
    int jobDefinitions,
    int workflowDefinitions,
    int fileChannels,
    int fileTemplates,
    int resourceQueues,
    int batchWindows,
    int businessCalendars,
    int quotaPolicies,
    int alertRoutings) {

  public static ConsoleJobBundleSummaryResponse from(ConfigSyncBundlePayload bundle) {
    int jobDefinitionCount = sizeOf(bundle.getJobDefinitions());
    int workflowDefinitionCount = sizeOf(bundle.getWorkflowDefinitions());
    int fileChannelCount = sizeOf(bundle.getFileChannels());
    int fileTemplateCount = sizeOf(bundle.getFileTemplates());
    int resourceQueueCount = sizeOf(bundle.getResourceQueues());
    int batchWindowCount = sizeOf(bundle.getBatchWindows());
    int businessCalendarCount = sizeOf(bundle.getBusinessCalendars());
    int quotaPolicyCount = sizeOf(bundle.getQuotaPolicies());
    int alertRoutingCount = sizeOf(bundle.getAlertRoutings());
    return new ConsoleJobBundleSummaryResponse(
        jobDefinitionCount,
        workflowDefinitionCount,
        fileChannelCount,
        fileTemplateCount,
        resourceQueueCount,
        batchWindowCount,
        businessCalendarCount,
        quotaPolicyCount,
        alertRoutingCount);
  }

  private static int sizeOf(List<?> values) {
    return values == null ? 0 : values.size();
  }
}
