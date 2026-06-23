package io.github.pinpols.batch.orchestrator.domain.param;

import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class UpdateInstanceProgressParam {
  private final String tenantId;
  private final Long id;
  private final String instanceStatus;
  private final Integer successPartitionCount;
  private final Integer failedPartitionCount;
  private final String resultSummary;
  private final Instant finishedAt;
  private final Long expectedVersion;

  /** ADR-012 失败终态时填；非 FAILED / PARTIAL_FAILED 应为 null。 */
  private final String failureClass;
}
