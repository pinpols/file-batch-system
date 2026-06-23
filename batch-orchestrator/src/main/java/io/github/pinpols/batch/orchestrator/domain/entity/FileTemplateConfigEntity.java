package io.github.pinpols.batch.orchestrator.domain.entity;

public record FileTemplateConfigEntity(
    Long id,
    String tenantId,
    String templateCode,
    String templateName,
    String templateType,
    String bizType,
    Boolean enabled,
    Integer version) {}
