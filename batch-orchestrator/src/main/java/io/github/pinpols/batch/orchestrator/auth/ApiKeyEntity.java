package io.github.pinpols.batch.orchestrator.auth;

import java.time.Instant;

/**
 * API Key 验证结果的精简投影 — 只取 filter 鉴权需要的字段。
 *
 * <p>V166 起新增 {@code keyHash} / {@code salt} / {@code keyHashAlgo} 三列,用于支持 PBKDF2 + per-key salt
 * (P1-1)。filter 拿候选行后由 {@link ApiKeyVerifier} 用 {@link
 * io.github.pinpols.batch.common.security.ApiKeyHasher} 常量时间比对。
 */
public record ApiKeyEntity(
    Long id,
    String tenantId,
    String keyName,
    String scopes,
    Boolean enabled,
    Instant expiresAt,
    String keyHash,
    String salt,
    String keyHashAlgo) {}
