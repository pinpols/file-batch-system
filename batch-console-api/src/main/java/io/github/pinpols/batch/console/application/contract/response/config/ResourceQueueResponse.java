package io.github.pinpols.batch.console.application.contract.response.config;

import static io.github.pinpols.batch.console.application.contract.response.config.ResponseFieldReader.booleanValue;
import static io.github.pinpols.batch.console.application.contract.response.config.ResponseFieldReader.instantValue;
import static io.github.pinpols.batch.console.application.contract.response.config.ResponseFieldReader.integerValue;
import static io.github.pinpols.batch.console.application.contract.response.config.ResponseFieldReader.longValue;
import static io.github.pinpols.batch.console.application.contract.response.config.ResponseFieldReader.stringValue;

import java.time.Instant;
import java.util.Map;
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

  public static ResourceQueueResponse from(Map<String, Object> row) {
    if (row == null) {
      return null;
    }
    return ResourceQueueResponse.builder()
        .id(longValue(row, "id"))
        .tenantId(stringValue(row, "tenantId", "tenant_id"))
        .queueCode(stringValue(row, "queueCode", "queue_code"))
        .queueName(stringValue(row, "queueName", "queue_name"))
        .queueType(stringValue(row, "queueType", "queue_type"))
        .maxRunningJobs(integerValue(row, "maxRunningJobs", "max_running_jobs"))
        .maxRunningPartitions(integerValue(row, "maxRunningPartitions", "max_running_partitions"))
        .maxQps(integerValue(row, "maxQps", "max_qps"))
        .workerGroup(stringValue(row, "workerGroup", "worker_group"))
        .resourceTag(stringValue(row, "resourceTag", "resource_tag"))
        .priorityPolicy(stringValue(row, "priorityPolicy", "priority_policy"))
        .fairShareWeight(integerValue(row, "fairShareWeight", "fair_share_weight"))
        .enabled(booleanValue(row, "enabled"))
        .description(stringValue(row, "description"))
        .createdAt(instantValue(row, "createdAt", "created_at"))
        .updatedAt(instantValue(row, "updatedAt", "updated_at"))
        .build();
  }
}
