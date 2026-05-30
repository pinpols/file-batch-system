package com.example.batch.console.domain.rbac.support;

import java.util.Set;

public record ConsoleUserAccount(
    String tenantId,
    String username,
    String displayName,
    String passwordHash,
    Set<String> authorities,
    boolean enabled) {}
