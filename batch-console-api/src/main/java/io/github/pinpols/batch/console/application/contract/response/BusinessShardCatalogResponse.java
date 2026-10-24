package io.github.pinpols.batch.console.application.contract.response;

import io.github.pinpols.batch.console.domain.entity.BusinessShardCatalogEntity;
import java.time.Instant;
import lombok.Builder;

/** Public shard catalog view; credentials remain outside the catalog. */
@Builder
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
    return BusinessShardCatalogResponse.builder()
        .placementKey(entity.getPlacementKey())
        .host(entity.getHost())
        .port(entity.getPort())
        .dbName(entity.getDbName())
        .secretRef(entity.getSecretRef())
        .poolMaxSize(entity.getPoolMaxSize())
        .enabled(entity.isEnabled())
        .description(entity.getDescription())
        .updatedAt(entity.getUpdatedAt())
        .updatedBy(entity.getUpdatedBy())
        .build();
  }
}
