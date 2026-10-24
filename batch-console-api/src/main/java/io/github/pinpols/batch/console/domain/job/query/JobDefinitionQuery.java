package io.github.pinpols.batch.console.domain.job.query;

import lombok.Builder;

import io.github.pinpols.batch.common.model.PageRequest;
@Builder
public record JobDefinitionQuery(
    String tenantId,
    String jobCode,
    String exactJobCode,
    String dependsOnJobCode,
    String jobName,
    String jobType,
    String workerGroup,
    String queueCode,
    String scheduleType,
    String triggerMode,
    Boolean hasUpstreamDependency,
    String monitoringCategory,
    Boolean enabled,
    PageRequest pageRequest) {

  /** 按租户全量查询，不带过滤条件。 */
  public static JobDefinitionQuery ofTenant(String tenantId, PageRequest pageRequest) {
    return builder().tenantId(tenantId).pageRequest(pageRequest).build();
  }
}
