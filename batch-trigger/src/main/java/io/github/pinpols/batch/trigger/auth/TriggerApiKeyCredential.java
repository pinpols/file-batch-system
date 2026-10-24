package io.github.pinpols.batch.trigger.auth;

import java.time.Instant;

/** 构造 Outbox 的内存快照保持一致，使回收任务路由到 Worker 池，而不是 */
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
