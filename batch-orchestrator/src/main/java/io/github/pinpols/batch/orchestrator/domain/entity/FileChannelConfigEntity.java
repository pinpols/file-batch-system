package io.github.pinpols.batch.orchestrator.domain.entity;

public record FileChannelConfigEntity(
    Long id,
    String tenantId,
    String channelCode,
    String channelName,
    String channelType,
    Boolean enabled) {}
