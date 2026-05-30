package com.example.batch.console.domain.rbac.web.response;

public record ConsoleUserAccountResponse(
    Long id,
    String tenantId,
    String username,
    String displayName,
    String authoritiesCsv,
    boolean enabled,
    String createdAt,
    String updatedAt) {}
