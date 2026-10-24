package io.github.pinpols.batch.console.domain.rbac.application.contract.response;

import io.github.pinpols.batch.console.domain.rbac.entity.ApiKeyEntity;
import java.time.Instant;
import lombok.Builder;

/** Public API key view; hash and KDF material are deliberately excluded. */
@Builder
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
    return ApiKeyResponse.builder()
        .id(entity.getId())
        .tenantId(entity.getTenantId())
        .keyName(entity.getKeyName())
        .keyPrefix(entity.getKeyPrefix())
        .scopes(entity.getScopes())
        .enabled(entity.getEnabled())
        .expiresAt(entity.getExpiresAt())
        .lastUsedAt(entity.getLastUsedAt())
        .createdBy(entity.getCreatedBy())
        .revokedBy(entity.getRevokedBy())
        .revokedAt(entity.getRevokedAt())
        .createdAt(entity.getCreatedAt())
        .build();
  }
}
