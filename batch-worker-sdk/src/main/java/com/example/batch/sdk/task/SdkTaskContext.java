package com.example.batch.sdk.task;

import java.util.Map;
import java.util.Objects;

/**
 * 任务执行上下文 — 由 SDK 框架从派单消息构造,传给 {@link SdkTaskHandler#execute(SdkTaskContext)}。
 *
 * <p>跟主项目 {@code com.example.batch.common.spi.task.TaskContext} 对齐,**结构等价**(平台→SDK 协议层契约, 同样的字段集),但
 * SDK 这边不引 batch-common 避免传递依赖膨胀。
 *
 * @param tenantId 租户 ID(必传)
 * @param jobCode 作业编码
 * @param taskInstanceId 本次执行的 task instance ID
 * @param taskId orchestrator 端 task 主键(用于 CLAIM / REPORT)
 * @param workerId 当前 worker 标识(SDK 在 register 时分配)
 * @param parameters 用户定义的任务参数(来自 job_definition.parameters JSON)
 * @param runtimeAttributes 平台注入的运行时属性(traceId / bizDate / pipelineInstanceId 等)
 */
public record SdkTaskContext(
    String tenantId,
    String jobCode,
    String taskInstanceId,
    Long taskId,
    String workerId,
    Map<String, Object> parameters,
    Map<String, Object> runtimeAttributes) {

  public SdkTaskContext {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(jobCode, "jobCode");
    Objects.requireNonNull(taskId, "taskId");
    parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    runtimeAttributes = runtimeAttributes == null ? Map.of() : Map.copyOf(runtimeAttributes);
  }
}
