package io.github.pinpols.batch.console.domain.rbac.application.contract.response;

public record ConsoleUserAccountResponse(
    Long id,
    String tenantId,
    String username,
    String displayName,
    String authoritiesCsv,
    boolean enabled,
    String createdAt,
    String updatedAt) {}
