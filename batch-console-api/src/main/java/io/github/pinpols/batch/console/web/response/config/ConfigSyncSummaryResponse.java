package io.github.pinpols.batch.console.web.response.config;

import io.github.pinpols.batch.console.web.request.config.ConfigSyncBundlePayload;
import java.util.List;

/** 配置同步包内各类配置的数量汇总。 */
public record ConfigSyncSummaryResponse(
    int jobDefinitions,
    int workflowDefinitions,
    int pipelineDefinitions,
    int fileChannels,
    int fileTemplates) {

  public static ConfigSyncSummaryResponse from(ConfigSyncBundlePayload bundle) {
    return new ConfigSyncSummaryResponse(
        sizeOf(bundle.getJobDefinitions()),
        sizeOf(bundle.getWorkflowDefinitions()),
        sizeOf(bundle.getPipelineDefinitions()),
        sizeOf(bundle.getFileChannels()),
        sizeOf(bundle.getFileTemplates()));
  }

  public int total() {
    return jobDefinitions
        + workflowDefinitions
        + pipelineDefinitions
        + fileChannels
        + fileTemplates;
  }

  private static int sizeOf(List<?> values) {
    return values == null ? 0 : values.size();
  }
}
