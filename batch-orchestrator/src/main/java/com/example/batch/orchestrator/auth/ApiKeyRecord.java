package com.example.batch.orchestrator.auth;

import java.time.Instant;

/** API Key 验证结果的精简投影 — 只取 filter 鉴权需要的字段。 */
public record ApiKeyRecord(
    Long id, String tenantId, String keyName, String scopes, Boolean enabled, Instant expiresAt) {}
