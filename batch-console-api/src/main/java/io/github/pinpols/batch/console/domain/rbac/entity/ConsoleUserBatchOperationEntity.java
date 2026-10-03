package io.github.pinpols.batch.console.domain.rbac.entity;

import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Data;

/** 批量开户操作记录，不包含初始密码。 */
@Data
public class ConsoleUserBatchOperationEntity {
  private UUID operationId;
  private UUID requestId;
  private int accountCount;
  private String tenantIds;
  private OffsetDateTime createdAt;
}
