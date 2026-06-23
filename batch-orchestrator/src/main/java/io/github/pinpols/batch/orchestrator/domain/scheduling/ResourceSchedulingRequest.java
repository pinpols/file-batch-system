package io.github.pinpols.batch.orchestrator.domain.scheduling;

import lombok.Data;

@Data
public class ResourceSchedulingRequest {

  private String tenantId;
  private String jobCode;
  private String queueCode;
  private String workerGroup;
  private String workerType;
  private String windowCode;
  private Integer priority;
  private int requestedPartitionCount = 1;
}
