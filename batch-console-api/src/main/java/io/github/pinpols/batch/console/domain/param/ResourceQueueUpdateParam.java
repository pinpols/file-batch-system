package io.github.pinpols.batch.console.domain.param;

import lombok.Builder;

/** 资源队列更新参数；应用层先合并既有值，SQL仍按租户与主键限定。 */
@Builder
public record ResourceQueueUpdateParam(
    String tenantId,
    Long id,
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
    String description) {}
