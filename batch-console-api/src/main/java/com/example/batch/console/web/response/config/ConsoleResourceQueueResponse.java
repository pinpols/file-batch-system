package com.example.batch.console.web.response.config;

import java.time.Instant;

public record ConsoleResourceQueueResponse(
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
    Instant updatedAt) {}
