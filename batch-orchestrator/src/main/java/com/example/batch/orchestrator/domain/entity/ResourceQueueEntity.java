package com.example.batch.orchestrator.domain.entity;

public record ResourceQueueEntity(
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
    String fairShareGroup,
    Integer burstLimit,
    String quotaResetPolicy,
    Integer groupSharedMaxRunningJobs,
    Boolean enabled) {}
