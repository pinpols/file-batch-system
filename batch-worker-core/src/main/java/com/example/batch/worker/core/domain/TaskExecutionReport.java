package com.example.batch.worker.core.domain;

import lombok.Data;

@Data
public class TaskExecutionReport {

  private Long taskId;
  private String tenantId;
  private String workerId;

  /** Worker 侧的 traceId，用于让 orchestrator 同一条任务实例的日志/审计能够串起来。 */
  private String traceId;

  private boolean success;
  private String code;
  private String message;
  private String resultSummary;
  private String errorCode;
  private String errorMessage;

  /**
   * 增量执行模式(ExecutionMode.INCREMENTAL)下 worker 上报的新水位高点。业务逻辑通过 {@code
   * ExecutionContext.getAttributes().put(PipelineRuntimeKeys.HIGH_WATER_MARK_OUT, ...)}
   * 写入;框架不自动推进。null 表示本次执行无水位变化,orchestrator 不更新 job_instance.high_water_mark_out。
   */
  private String highWaterMarkOut;
}
