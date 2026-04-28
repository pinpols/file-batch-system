package com.example.batch.orchestrator.controller.request;

import lombok.Data;

@Data
public class TaskExecutionReportDto {

  private Long taskId;
  private String tenantId;
  private String workerId;

  /** Worker 侧 traceId，用于在 orchestrator 侧把状态推进/重试/补偿日志串起来。 */
  private String traceId;

  private boolean success;
  private String code;
  private String message;
  private String resultSummary;
  private String errorCode;
  private String errorMessage;

  /** Worker 上报的 i18n message key,跨进程传递到 orchestrator 持久化。 */
  private String errorKey;

  /** Worker 上报的 i18n 占位符参数 JSON 数组。 */
  private String errorArgs;

  /** 增量执行模式下 worker 上报的新水位高点。仅在成功路径回写 {@code job_instance.high_water_mark_out}; null 表示无变化。 */
  private String highWaterMarkOut;
}
