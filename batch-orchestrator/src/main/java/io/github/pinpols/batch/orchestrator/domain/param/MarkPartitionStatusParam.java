package io.github.pinpols.batch.orchestrator.domain.param;

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
}
