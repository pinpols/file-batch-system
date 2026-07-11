package io.github.pinpols.batch.console.domain.job.web.response;

import io.github.pinpols.batch.console.web.request.config.ConfigSyncBundlePayload;
import java.util.List;

/** Job Bundle 内各类配置的数量汇总（10 类，键与历史 summary map 一致）。 */
public record ConsoleJobBundleSummaryResponse(
    int jobDefinitions,
    int workflowDefinitions,
    int pipelineDefinitions,
    int fileChannels,
    int fileTemplates,
    int resourceQueues,
    int batchWindows,
    int businessCalendars,
    int quotaPolicies,
    int alertRoutings) {

  public static ConsoleJobBundleSummaryResponse from(ConfigSyncBundlePayload bundle) {
    return new ConsoleJobBundleSummaryResponse(
        sizeOf(bundle.getJobDefinitions()),
        sizeOf(bundle.getWorkflowDefinitions()),
        sizeOf(bundle.getPipelineDefinitions()),
        sizeOf(bundle.getFileChannels()),
        sizeOf(bundle.getFileTemplates()),
        sizeOf(bundle.getResourceQueues()),
        sizeOf(bundle.getBatchWindows()),
        sizeOf(bundle.getBusinessCalendars()),
        sizeOf(bundle.getQuotaPolicies()),
        sizeOf(bundle.getAlertRoutings()));
  }

  private static int sizeOf(List<?> values) {
    return values == null ? 0 : values.size();
  }
}
