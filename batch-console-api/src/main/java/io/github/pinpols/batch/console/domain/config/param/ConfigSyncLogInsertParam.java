package io.github.pinpols.batch.console.domain.config.param;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

/** 同步审计创建参数；仅生成的主键由JDBC回填。 */
@Getter
@Builder
public class ConfigSyncLogInsertParam {
  @Setter
  private Long id;

  private final String tenantId;
  private final String syncDirection;
  private final String sourceEnv;
  private final String targetEnv;
  private final String configTypes;
  private final Integer totalItems;
  private final Integer successItems;
  private final Integer failedItems;
  private final Integer skippedItems;
  private final String syncStatus;
  private final String detailJson;
  private final String operatorId;
}
