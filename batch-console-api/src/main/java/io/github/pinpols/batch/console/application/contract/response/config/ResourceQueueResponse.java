package io.github.pinpols.batch.console.application.contract.response.config;

import io.github.pinpols.batch.console.domain.ops.view.ResourceQueueRow;
import java.time.Instant;
import lombok.Builder;

/** 资源队列响应。 */
@Builder
public record ResourceQueueResponse(
    Long id,
    String tenantId,
    String queueCode,
    String queueName,
    String queueType,
    Integer maxRunningJobs,
    Integer maxRunningPartitions,
    Integer maxQps,
    String workerGroup,
    String resourceTag,
    String priorityPolicy,
    Integer fairShareWeight,
    Boolean enabled,
    String description,
    Instant createdAt,
    Instant updatedAt) {

  public static ResourceQueueResponse from(ResourceQueueRow row) {
    if (row == null) {
      return null;
    }
    return ResourceQueueResponse.builder()
        .id(row.id())
        .tenantId(row.tenantId())
        .queueCode(row.queueCode())
        .queueName(row.queueName())
        .queueType(row.queueType())
        .maxRunningJobs(row.maxRunningJobs())
        .maxRunningPartitions(row.maxRunningPartitions())
        .maxQps(row.maxQps())
        .workerGroup(row.workerGroup())
        .resourceTag(row.resourceTag())
        .priorityPolicy(row.priorityPolicy())
        .fairShareWeight(row.fairShareWeight())
        .enabled(row.enabled())
        .description(row.description())
        .createdAt(row.createdAt())
        .updatedAt(row.updatedAt())
        .build();
  }
}
