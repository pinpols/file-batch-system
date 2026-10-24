package io.github.pinpols.batch.trigger.auth;

import java.time.Instant;

/** Minimal read projection used to authenticate tenant API keys for external EVENT launches. */
public record TriggerApiKeyCredential(
    Long id,
    String tenantId,
    String keyName,
    String scopes,
    Boolean enabled,
    Instant expiresAt,
    String keyHash,
    String salt,
    String keyHashAlgo) {}
