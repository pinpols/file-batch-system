package io.github.pinpols.batch.console.web.response.config;

import static io.github.pinpols.batch.console.web.response.config.ResponseFieldReader.booleanValue;
import static io.github.pinpols.batch.console.web.response.config.ResponseFieldReader.instantValue;
import static io.github.pinpols.batch.console.web.response.config.ResponseFieldReader.integerValue;
import static io.github.pinpols.batch.console.web.response.config.ResponseFieldReader.longValue;
import static io.github.pinpols.batch.console.web.response.config.ResponseFieldReader.stringValue;

import java.time.Instant;
import java.util.Map;

/** 资源队列响应。 */
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
    return new ResourceQueueResponse(
        longValue(row, "id"),
        stringValue(row, "tenantId", "tenant_id"),
        stringValue(row, "queueCode", "queue_code"),
        stringValue(row, "queueName", "queue_name"),
        stringValue(row, "queueType", "queue_type"),
        integerValue(row, "maxRunningJobs", "max_running_jobs"),
        integerValue(row, "maxRunningPartitions", "max_running_partitions"),
        integerValue(row, "maxQps", "max_qps"),
        stringValue(row, "workerGroup", "worker_group"),
        stringValue(row, "resourceTag", "resource_tag"),
        stringValue(row, "priorityPolicy", "priority_policy"),
        integerValue(row, "fairShareWeight", "fair_share_weight"),
        booleanValue(row, "enabled"),
        stringValue(row, "description"),
        instantValue(row, "createdAt", "created_at"),
        instantValue(row, "updatedAt", "updated_at"));
  }
}
