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

  /**
   * 增量执行模式(ExecutionMode.INCREMENTAL)的水位起点,从 TaskDispatchMessage 透传过来。 Worker 业务逻辑可通过 {@code
   * ExecutionContext.getAttributes().get(PipelineRuntimeKeys.HIGH_WATER_MARK_IN)} 读取并 拼接 SQL,例
   * `WHERE update_time > :highWaterMarkIn`。
   */
  private String highWaterMarkIn;

  /** 当前 task 所属 partition 的 1-based 序号(claim 拿,源头 job_partition.partition_no)。 */
  private Integer partitionNo;

  /** 本次 job_instance 的 partition 总数(claim 拿,源头 job_instance.expected_partition_count)。 */
  private Integer partitionCount;

  /** partition 业务标识(claim 拿,源头 job_partition.partition_key)。 */
  private String partitionKey;
}
