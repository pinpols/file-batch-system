package io.github.pinpols.batch.trigger.domain.query;

import java.time.Instant;
import lombok.Data;

/** 已超过 readiness 等待窗口、需要终结为 READINESS_TIMEOUT 的待观察记录。 */
@Data
public class ExpiredReadinessWaitRow {

  private String tenantId;
  private Long workflowDefinitionId;
  private Instant scheduledFireTime;
  private Instant deadline;
}
