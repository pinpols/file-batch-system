package com.example.batch.console.domain.rbac.support;

import java.util.Set;

public record ConsolePrincipal(String username, String tenantId, Set<String> authorities) {}
