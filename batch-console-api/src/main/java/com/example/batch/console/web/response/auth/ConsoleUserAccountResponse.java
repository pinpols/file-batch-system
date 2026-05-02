package com.example.batch.console.web.response.auth;

public record ConsoleUserAccountResponse(
    Long id,
    String tenantId,
    String username,
    String displayName,
    String authoritiesCsv,
    boolean enabled,
    String createdAt,
    String updatedAt) {}
