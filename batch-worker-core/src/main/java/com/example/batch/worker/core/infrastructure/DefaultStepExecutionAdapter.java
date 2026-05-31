package com.example.batch.worker.core.infrastructure;

import com.example.batch.common.spi.task.BatchTaskExecutor;
import com.example.batch.common.spi.task.BatchTaskExecutorRegistry;
import com.example.batch.common.spi.task.TaskContext;
import com.example.batch.common.spi.task.TaskResult;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.worker.core.domain.StepExecutionRequest;
import com.example.batch.worker.core.domain.StepExecutionResponse;
import com.example.batch.worker.core.support.StepExecutionAdapter;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@link StepExecutionAdapter} 的默认实现 — 充当 {@link BatchTaskExecutor} SPI 的路由器。
 *
 * <p>本类把 worker-core 派发来的 task 路由到对应原子任务 SPI:
 *
 * <ol>
 *   <li>执行器子类型:优先取 payload 里的 {@code taskType}(SPI 协议字段,如 shell/sql/stored_proc/http), 缺失时回退 {@code
 *       stepCode}(= job_task.task_type)。job_type=SPI 的任务,task_type 也是 "SPI", 真正的子类型只在 payload
 *       里,故必须优先读 payload。
 *   <li>参数:解析 payload(= job_definition.default_params / launch 参数 JSON)为 Map 传给 {@link
 *       TaskContext}。
 * </ol>
 *
 * <p>调用顺序(由 Spring {@code @Primary} 控制):
 *
 * <ol>
 *   <li>4 个 Pipeline worker module(import/export/process/dispatch)各自的 {@code
 *       XxxStepExecutionAdapter} 带 {@code @Primary},Spring 解析 {@link StepExecutionAdapter} 时优先选它们
 *   <li>本类作为 fallback,只在无 Pipeline 实现匹配时被注入(SPI worker 进程 / worker-core 单独跑)
 * </ol>
 *
 * <p>实际生产中 4 个 pipeline worker 都注册了 {@code @Primary},Pipeline 任务永远走 Pipeline 路径; 专用
 * batch-worker-atomic 进程不含任何 Pipeline adapter,故原子任务派发命中本类。设计见 {@code
 * docs/design/task-spi-design.md} §4.4。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultStepExecutionAdapter implements StepExecutionAdapter {

  private final BatchTaskExecutorRegistry registry;

  /** payload 里携带执行器子类型的字段名(SPI 协议)。 */
  private static final String PARAM_TASK_TYPE = "taskType";

  /**
   * executionContext 里原始 payload(JSON 字符串)的键,见 {@code
   * DefaultTaskExecutionWrapper#buildExecutionContext}。
   */
  private static final String CONTEXT_PAYLOAD = "payload";

  @Override
  public StepExecutionResponse execute(StepExecutionRequest request) {
    Map<String, Object> parameters = extractParameters(request);
    String taskType = resolveExecutorType(request, parameters);
    BatchTaskExecutor executor = registry.find(taskType);
    if (executor == null) {
      log.warn(
          "no BatchTaskExecutor registered for taskType={}, registered={}",
          taskType,
          registry.registeredTypes());
      return new StepExecutionResponse(
          false, "NO_EXECUTOR", "no BatchTaskExecutor registered for taskType=" + taskType);
    }
    TaskContext ctx =
        new TaskContext(
            request.tenantId(),
            request.jobCode(),
            // taskInstanceId 暂不在 StepExecutionRequest 里;需要时从 context 抽
            null,
            request.workerId(),
            parameters,
            request.context());
    try {
      TaskResult result = executor.execute(ctx);
      return toResponse(result);
    } catch (RuntimeException ex) {
      log.error("BatchTaskExecutor uncaught exception, taskType={}", taskType, ex);
      String msg = ex.getMessage() == null ? ex.getClass().getName() : ex.getMessage();
      return new StepExecutionResponse(false, "EXECUTOR_FAILURE", msg);
    }
  }

  /** 解析 payload(default_params / launch 参数 JSON)为参数 Map;缺失或非法 JSON 对象时返回空 Map。 */
  private static Map<String, Object> extractParameters(StepExecutionRequest request) {
    Map<String, Object> context = request.context();
    Object payload = context == null ? null : context.get(CONTEXT_PAYLOAD);
    if (!(payload instanceof String payloadJson) || payloadJson.isBlank()) {
      return Map.of();
    }
    try {
      Object parsed = JsonUtils.fromJson(payloadJson, Object.class);
      if (parsed instanceof Map<?, ?> map) {
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) map;
        return typed;
      }
    } catch (RuntimeException ex) {
      log.warn("task payload 非合法 JSON 对象,以空参数执行: {}", ex.getMessage());
    }
    return Map.of();
  }

  /** 路由用的执行器子类型:优先 payload.taskType,回退 stepCode(= job_task.task_type)。 */
  private static String resolveExecutorType(
      StepExecutionRequest request, Map<String, Object> parameters) {
    Object subType = parameters.get(PARAM_TASK_TYPE);
    if (subType instanceof String s && !s.isBlank()) {
      return s.trim();
    }
    return request.stepCode();
  }

  private static StepExecutionResponse toResponse(TaskResult result) {
    if (result.success()) {
      return StepExecutionResponse.successResponse();
    }
    return new StepExecutionResponse(false, "TASK_FAILED", result.message());
  }
}
