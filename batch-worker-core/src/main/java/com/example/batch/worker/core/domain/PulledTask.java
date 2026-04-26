package com.example.batch.worker.core.domain;

import lombok.Data;

/**
 * Worker 从 Orchestrator 拉取到的原始任务载体。 包含任务的身份标识（taskId、jobInstanceId）、路由信息（tenantId、workerId） 以及原始业务
 * payload，供后续 CLAIM → EXECUTE 流程使用。
 */
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
