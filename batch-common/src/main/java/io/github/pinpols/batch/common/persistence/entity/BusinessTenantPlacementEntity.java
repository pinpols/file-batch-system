package io.github.pinpols.batch.common.persistence.entity;

import java.time.Instant;
import lombok.Data;

/**
 * {@code batch.business_tenant_placement} 行:租户 → biz 分片(placement key)显式映射。
 *
 * <p>P2 tenant-routing 表驱动 placement 的载体;只承载映射,不含连接账密(凭据走 secrets)。
 */
@Data
public class BusinessTenantPlacementEntity {

  private String tenantId;
  private String placementKey;
  private Instant updatedAt;
  private String updatedBy;
}
