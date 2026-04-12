package com.example.batch.worker.core.domain;

import lombok.Data;

@Data
public class PulledTask {

  private String taskId;
  private String taskType;
  private String jobCode;
  private String tenantId;
  private String workerId;
  private String traceId;
  private String businessKey;
  private Long jobInstanceId;
  private Long jobPartitionId;
  private Integer taskSeq;
  private String idempotencyKey;
  private String payload;
}
