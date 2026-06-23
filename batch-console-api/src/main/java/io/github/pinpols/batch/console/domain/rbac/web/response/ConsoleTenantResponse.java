package io.github.pinpols.batch.console.domain.rbac.web.response;

public record ConsoleTenantResponse(
    Long id,
    String tenantId,
    String tenantName,
    String status,
    String description,
    String createdBy,
    String createdAt,
    String updatedAt) {}
