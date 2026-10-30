package io.github.pinpols.batch.console.domain.observability.application.contract.response;

import io.github.pinpols.batch.console.domain.observability.view.dashboard.TenantUsageView;

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

  public static ConsoleTenantUsageResponse from(TenantUsageView view) {
    if (view == null) {
      return null;
    }
    return new ConsoleTenantUsageResponse(
        view.tenantId(),
        view.jobDefinitions(),
        view.workflowDefinitions(),
        view.fileChannels(),
        view.fileTemplates(),
        view.recentJobInstances(),
        view.recentFiles(),
        view.periodDays());
  }
}
