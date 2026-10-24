package io.github.pinpols.batch.trigger.auth;

import java.time.Instant;

/** Authenticated API-key identity; contains no secret or hash material. */
public record TriggerApiKeyPrincipal(
    Long id, String tenantId, String keyName, String scopes, Instant expiresAt) {}
