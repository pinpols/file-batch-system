package io.github.pinpols.batch.console.infrastructure.config;

import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigCopyRequest.ConfigType;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigDiffPreviewResponse.ConfigDiffItem;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigDiffPreviewResponse.ConfigImpactItem;
import java.util.ArrayList;
import java.util.List;

/** 根据配置差异生成复制前影响提示。 */
final class TenantConfigCopyImpactAnalyzer {
  private TenantConfigCopyImpactAnalyzer() {}

  static List<ConfigImpactItem> analyze(List<ConfigDiffItem> items) {
    List<ConfigImpactItem> impacts = new ArrayList<>();
    for (ConfigDiffItem item : items) {
      if ("UNCHANGED".equals(item.action())) {
        continue;
      }
      boolean configImpactRequiresEnvironmentReview =
          ConfigType.FILE_CHANNEL.name().equals(item.configType())
              || ConfigType.RESOURCE_QUEUE.name().equals(item.configType())
              || ConfigType.BATCH_WINDOW.name().equals(item.configType())
              || ConfigType.BUSINESS_CALENDAR.name().equals(item.configType());
      if (configImpactRequiresEnvironmentReview) {
        impacts.add(new ConfigImpactItem(
            "ENV_SPECIFIC_REVIEW",
            item.configType() + ":" + item.configKey(),
            "review endpoint, capacity, calendar or batch window values before apply"));
      } else if (ConfigType.JOB_DEFINITION.name().equals(item.configType())) {
        impacts.add(new ConfigImpactItem(
            "JOB_CONFIG_CHANGE",
            item.configKey(),
            "schedule, queue, worker group or default parameters may affect future launches"));
      } else if (ConfigType.FILE_TEMPLATE.name().equals(item.configType())) {
        impacts.add(new ConfigImpactItem(
            "FILE_CONTRACT_CHANGE",
            item.configKey(),
            "template format, validation, mapping or SQL may affect import/export results"));
      }
    }
    return impacts;
  }
}
