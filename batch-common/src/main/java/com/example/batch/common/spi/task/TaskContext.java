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
}
