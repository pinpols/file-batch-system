package io.github.pinpols.batch.console.domain.observability.web.response;

import static io.github.pinpols.batch.console.domain.observability.web.response.ObservabilityResponseFieldReader.integerValue;
import static io.github.pinpols.batch.console.domain.observability.web.response.ObservabilityResponseFieldReader.longValue;
import static io.github.pinpols.batch.console.domain.observability.web.response.ObservabilityResponseFieldReader.stringValue;

import java.util.Map;

/** dashboard tenant-usage 响应：配置数量 + 近期实例 / 文件处理量（固定字段）。 */
public record ConsoleTenantUsageResponse(
    String tenantId,
    Long jobDefinitions,
    Long workflowDefinitions,
    Long fileChannels,
    Long fileTemplates,
    Long recentJobInstances,
    Long recentFiles,
    Integer periodDays) {

  public static ConsoleTenantUsageResponse from(Map<String, Object> row) {
    if (row == null) {
      return null;
    }
    return new ConsoleTenantUsageResponse(
        stringValue(row, "tenantId"),
        longValue(row, "jobDefinitions"),
        longValue(row, "workflowDefinitions"),
        longValue(row, "fileChannels"),
        longValue(row, "fileTemplates"),
        longValue(row, "recentJobInstances"),
        longValue(row, "recentFiles"),
        integerValue(row, "periodDays"));
  }
}
