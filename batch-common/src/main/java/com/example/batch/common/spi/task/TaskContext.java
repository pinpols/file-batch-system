package com.example.batch.common.spi.task;

import java.util.Map;
import java.util.Objects;

/**
 * {@link BatchTaskExecutor#execute(TaskContext)} 的入参 — 携带任务身份 + 用户参数 + 框架属性。
 *
 * <p>不暴露 Spring {@code ApplicationContext},所有依赖通过 SPI 实现类的构造器注入(Spring beans)或显式字段(POJO)。 这样保证:
 *
 * <ul>
 *   <li>第三方 jar 插件可不引 Spring 完整体
 *   <li>测试容易(构造 record 即可,不需 spring context)
 *   <li>禁止 SPI 实现绕过受控接口直接 grab framework beans
 * </ul>
 *
 * <p>Phase 1 字段最小集 — 后续 PR 按需扩展(如 {@code BatchTimezoneProvider} / {@code MeterRegistry} / {@code
 * BatchProperties} / streaming output collector 等),保持向后兼容(record 加字段需配合构造器重载)。
 *
 * @param tenantId 租户 ID,业务必传
 * @param jobCode 作业编码
 * @param taskInstanceId 本次执行的 task instance ID(可空,例如离线测试)
 * @param workerId 执行 worker 标识(可空)
 * @param parameters 用户定义的任务参数(来自 {@code job_definition.parameters} JSON)
 * @param runtimeAttributes 框架注入的运行时属性(traceId / bizDate / pipelineInstanceId 等)
 */
public record TaskContext(
    String tenantId,
    String jobCode,
    String taskInstanceId,
    String workerId,
    Map<String, Object> parameters,
    Map<String, Object> runtimeAttributes) {

  public TaskContext {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(jobCode, "jobCode");
    parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    runtimeAttributes = runtimeAttributes == null ? Map.of() : Map.copyOf(runtimeAttributes);
  }

  /**
   * ADR-026 dry-run 演练标记。orchestrator 在 task 派发链路把 {@code dryRun=true} 透传到 {@link
   * #runtimeAttributes} 顶层(主路径);兼容旧调用方塞 {@link #parameters} 顶层。任意一方为 true 即视为 dry-run。
   *
   * <p>语义:dry-run = 演练,executor 必须不发出任何真副作用(不发 SQL / 不 fork 进程 / 不发 HTTP / 不调存过), 直接返回成功并通过 {@code
   * TaskResult.output["plannedAction"]} 把"会执行什么"反馈给运维。
   */
  public boolean isDryRun() {
    return readDryRunFlag(runtimeAttributes) || readDryRunFlag(parameters);
  }

  private static boolean readDryRunFlag(Map<String, Object> source) {
    Object value = source.get("dryRun");
    if (value instanceof Boolean b) {
      return b;
    }
    return value != null && "true".equalsIgnoreCase(String.valueOf(value));
  }
}
