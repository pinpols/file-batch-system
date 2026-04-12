package com.example.batch.console.web.response;

import java.time.Instant;
import java.util.Set;

public record ConsoleAuthTokenResponse(
    String accessToken,
    String tokenType,
    Instant issuedAt,
    Instant expiresAt,
    String username,
    String tenantId,
    Set<String> authorities) {}
