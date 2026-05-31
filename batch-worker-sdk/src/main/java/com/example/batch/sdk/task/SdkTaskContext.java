package com.example.batch.sdk.task;

import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

/**
 * 任务执行上下文 — 由 SDK 框架从派单消息构造,传给 {@link SdkTaskHandler#execute(SdkTaskContext)}。
 *
 * <p>跟主项目 {@code com.example.batch.common.spi.task.TaskContext} 对齐,**结构等价**(平台→SDK 协议层契约, 同样的字段集),但
 * SDK 这边不引 batch-common 避免传递依赖膨胀。
 *
 * <p>Phase 2 §2.2:新增 {@link #schedulingContext},并暴露 7 个便捷 getter ({@link #bizDate()} / {@link
 * #prevBizDate()} / {@link #nextBizDate()} / {@link #isHoliday()} / {@link #attemptNo()} / {@link
 * #triggerCode()} / {@link #workflowRunId()}),让 handler 直接拿调度事实做增量逻辑,无需回调平台。
 *
 * @param tenantId 租户 ID(必传)
 * @param jobCode 作业编码
 * @param taskInstanceId 本次执行的 task instance ID
 * @param taskId orchestrator 端 task 主键(用于 CLAIM / REPORT)
 * @param workerId 当前 worker 标识(SDK 在 register 时分配)
 * @param parameters 用户定义的任务参数(来自 job_definition.parameters JSON)
 * @param runtimeAttributes 平台注入的运行时属性(traceId / bizDate / pipelineInstanceId 等)
 * @param schedulingContext Phase 2 调度上下文;老平台未下发时为 null,便捷 getter 一律 null-safe
 */
public record SdkTaskContext(
    String tenantId,
    String jobCode,
    String taskInstanceId,
    Long taskId,
    String workerId,
    Map<String, Object> parameters,
    Map<String, Object> runtimeAttributes,
    SdkSchedulingContext schedulingContext) {

  public SdkTaskContext {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(jobCode, "jobCode");
    Objects.requireNonNull(taskId, "taskId");
    parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    runtimeAttributes = runtimeAttributes == null ? Map.of() : Map.copyOf(runtimeAttributes);
  }

  /** 7 参兼容构造器 —— Phase 2 前的构造方式继续可用,schedulingContext 走 null。 */
  public SdkTaskContext(
      String tenantId,
      String jobCode,
      String taskInstanceId,
      Long taskId,
      String workerId,
      Map<String, Object> parameters,
      Map<String, Object> runtimeAttributes) {
    this(tenantId, jobCode, taskInstanceId, taskId, workerId, parameters, runtimeAttributes, null);
  }

  /** 实例业务日;无调度上下文时返回 null。 */
  public LocalDate bizDate() {
    return schedulingContext == null ? null : schedulingContext.bizDate();
  }

  /** 前一个业务日(近似:跳过周末);无调度上下文时返回 null。 */
  public LocalDate prevBizDate() {
    return schedulingContext == null ? null : schedulingContext.prevBizDate();
  }

  /** 下一个业务日(近似:跳过周末);无调度上下文时返回 null。 */
  public LocalDate nextBizDate() {
    return schedulingContext == null ? null : schedulingContext.nextBizDate();
  }

  /** 是否节假日(当前语义=周末);无调度上下文时返回 null。 */
  public Boolean isHoliday() {
    return schedulingContext == null ? null : schedulingContext.isHoliday();
  }

  /** 本次执行尝试序号(retry/reclaim 递增);无调度上下文时返回 null。 */
  public Integer attemptNo() {
    return schedulingContext == null ? null : schedulingContext.attemptNo();
  }

  /** 触发来源编码(平台暂无来源列,当前恒 null)。 */
  public String triggerCode() {
    return schedulingContext == null ? null : schedulingContext.triggerCode();
  }

  /** 所属 workflow run(平台暂无来源列,当前恒 null)。 */
  public Long workflowRunId() {
    return schedulingContext == null ? null : schedulingContext.workflowRunId();
  }
}
