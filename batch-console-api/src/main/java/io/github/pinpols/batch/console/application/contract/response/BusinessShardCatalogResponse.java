package io.github.pinpols.batch.console.application.contract.response;

import io.github.pinpols.batch.console.domain.entity.BusinessShardCatalogEntity;
import java.time.Instant;

/** Public shard catalog view; credentials remain outside the catalog. */
public record BusinessShardCatalogResponse(
    String placementKey,
    String host,
    int port,
    String dbName,
    String secretRef,
    Integer poolMaxSize,
    boolean enabled,
    String description,
    Instant updatedAt,
    String updatedBy) {

  public static BusinessShardCatalogResponse from(BusinessShardCatalogEntity entity) {
    return new BusinessShardCatalogResponse(
        entity.getPlacementKey(),
        entity.getHost(),
        entity.getPort(),
        entity.getDbName(),
        entity.getSecretRef(),
        entity.getPoolMaxSize(),
        entity.isEnabled(),
        entity.getDescription(),
        entity.getUpdatedAt(),
        entity.getUpdatedBy());
  }
}
