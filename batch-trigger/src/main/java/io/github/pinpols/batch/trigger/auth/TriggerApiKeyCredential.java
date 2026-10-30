package io.github.pinpols.batch.trigger.auth;

import java.time.Instant;

/** Trigger API 密钥认证使用的凭据投影，包含所属租户、授权范围及哈希信息。 */
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
