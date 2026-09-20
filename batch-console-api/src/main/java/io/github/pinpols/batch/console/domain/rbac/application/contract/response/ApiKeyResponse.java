package io.github.pinpols.batch.console.domain.rbac.application.contract.response;

import io.github.pinpols.batch.console.domain.rbac.entity.ApiKeyEntity;
import java.time.Instant;

/** Public API key view; hash and KDF material are deliberately excluded. */
public record ApiKeyResponse(
    Long id,
    String tenantId,
    String keyName,
    String keyPrefix,
    String scopes,
    Boolean enabled,
    Instant expiresAt,
    Instant lastUsedAt,
    String createdBy,
    String revokedBy,
    Instant revokedAt,
    Instant createdAt) {

  public static ApiKeyResponse from(ApiKeyEntity entity) {
    return new ApiKeyResponse(
        entity.getId(),
        entity.getTenantId(),
        entity.getKeyName(),
        entity.getKeyPrefix(),
        entity.getScopes(),
        entity.getEnabled(),
        entity.getExpiresAt(),
        entity.getLastUsedAt(),
        entity.getCreatedBy(),
        entity.getRevokedBy(),
        entity.getRevokedAt(),
        entity.getCreatedAt());
  }
}
