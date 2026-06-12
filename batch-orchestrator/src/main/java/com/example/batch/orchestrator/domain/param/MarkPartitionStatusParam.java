package com.example.batch.orchestrator.domain.param;

import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class MarkPartitionStatusParam {
  private final String tenantId;
  private final Long id;
  private final String partitionStatus;
  private final String runningStatus;
  private final String terminalStatus1;
  private final String terminalStatus2;
  private final String terminalStatus3;
  private final String terminalStatus4;
  private final Long expectedVersion;

  /** Citus IMMUTABLE fix: 替代 CASE 内的 current_timestamp，由调用方传入终态时刻。 */
  private final Instant finishedAt;
}
