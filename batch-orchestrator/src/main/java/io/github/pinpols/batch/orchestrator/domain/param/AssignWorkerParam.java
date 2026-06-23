package io.github.pinpols.batch.orchestrator.domain.param;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AssignWorkerParam {
  private final String tenantId;
  private final Long id;
  private final String assignedWorkerCode;
  private final String taskStatus;
  private final String readyStatus;
  private final Long expectedVersion;
}
