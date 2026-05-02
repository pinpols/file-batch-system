package com.example.batch.orchestrator.domain.entity;

public record FileChannelConfigEntity(
    Long id,
    String tenantId,
    String channelCode,
    String channelName,
    String channelType,
    Boolean enabled) {}
