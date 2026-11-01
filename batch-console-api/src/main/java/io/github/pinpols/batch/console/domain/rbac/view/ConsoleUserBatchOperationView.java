package io.github.pinpols.batch.console.domain.rbac.view;

import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Data;

/** 批量开户操作的查询投影，不包含初始密码。 */
@Data
public class ConsoleUserBatchOperationView {
  private UUID operationId;
  private UUID requestId;
  private int accountCount;
  private String tenantIds;
  private OffsetDateTime createdAt;
}
