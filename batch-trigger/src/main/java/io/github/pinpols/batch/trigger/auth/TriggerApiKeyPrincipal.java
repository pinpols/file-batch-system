package io.github.pinpols.batch.trigger.auth;

import java.time.Instant;

/** 已认证的 API Key 身份信息，不包含密钥或哈希内容。 */
public record TriggerApiKeyPrincipal(
    Long id, String tenantId, String keyName, String scopes, Instant expiresAt) {}
