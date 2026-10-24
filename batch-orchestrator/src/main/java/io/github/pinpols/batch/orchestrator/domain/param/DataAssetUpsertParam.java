package io.github.pinpols.batch.orchestrator.domain.param;

import java.util.Objects;
import lombok.Builder;

/** 一次资产身份与归属写入。 */
@Builder
public record DataAssetUpsertParam(
    String tenantId,
    String assetCode,
    String assetType,
    String displayName,
    String ownerJobCode) {

  public DataAssetUpsertParam {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(assetCode, "assetCode");
    Objects.requireNonNull(assetType, "assetType");
  }
}
