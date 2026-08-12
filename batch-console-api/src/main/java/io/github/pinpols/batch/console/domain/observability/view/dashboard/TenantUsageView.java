package io.github.pinpols.batch.console.domain.observability.view.dashboard;

/** Dashboard 租户配置和近期运行量统计。 */
public record TenantUsageView(
    String tenantId,
    Long jobDefinitions,
    Long workflowDefinitions,
    Long fileChannels,
    Long fileTemplates,
    Long recentJobInstances,
    Long recentFiles,
    Integer periodDays) {}
