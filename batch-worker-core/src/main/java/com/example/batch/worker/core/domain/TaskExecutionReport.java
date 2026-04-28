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
   * i18n message key (来自 BizException.of)。Worker 侧 BizException 命中 i18n key 时填入, orchestrator 持久化到
   * job_task.error_key,console 读路径按当前 Locale 重渲染。null 表示老 literal / 第三方异常,errorMessage 是唯一展示来源。
   */
  private String errorKey;

  /** i18n 占位符参数 JSON 数组,与 errorKey 一起跨进程传递。 */
  private String errorArgs;

  /**
   * 增量执行模式(ExecutionMode.INCREMENTAL)下 worker 上报的新水位高点。业务逻辑通过 {@code
   * ExecutionContext.getAttributes().put(PipelineRuntimeKeys.HIGH_WATER_MARK_OUT, ...)}
   * 写入;框架不自动推进。null 表示本次执行无水位变化,orchestrator 不更新 job_instance.high_water_mark_out。
   */
  private String highWaterMarkOut;
}
