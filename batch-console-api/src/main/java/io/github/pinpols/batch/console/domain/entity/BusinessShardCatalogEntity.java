package io.github.pinpols.batch.console.domain.entity;

import java.time.Instant;
import lombok.Data;

/**
 * {@code batch.business_shard_catalog} 行:biz 分片目录(P2 tenant-routing)。
 *
 * <p>只承载分片位置(host/port/db)+ 状态,**不含账密**(secretRef 仅凭据引用名;凭据走 secrets)。
 */
@Data
public class BusinessShardCatalogEntity {

  private String placementKey;
  private String host;
  private int port;
  private String dbName;
  private String secretRef;
  private Integer poolMaxSize;
  private boolean enabled;
  private String description;
  private Instant updatedAt;
  private String updatedBy;
}
