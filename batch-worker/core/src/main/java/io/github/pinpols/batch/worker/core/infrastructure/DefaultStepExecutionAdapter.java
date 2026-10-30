package io.github.pinpols.batch.worker.core.infrastructure;

import io.github.pinpols.batch.common.enums.TaskErrorCodes;
import io.github.pinpols.batch.common.logging.LogSanitizer;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.plugin.ReplayCapabilityDeclaration;
import io.github.pinpols.batch.common.spi.task.BatchTaskExecutor;
import io.github.pinpols.batch.common.spi.task.BatchTaskExecutorRegistry;
import io.github.pinpols.batch.common.spi.task.TaskContext;
import io.github.pinpols.batch.common.spi.task.TaskResult;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.worker.core.domain.StepExecutionRequest;
import io.github.pinpols.batch.worker.core.domain.StepExecutionResponse;
import io.github.pinpols.batch.worker.core.support.StepExecutionAdapter;
import io.github.pinpols.batch.worker.core.support.TaskExecutionClient;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@link StepExecutionAdapter} 的默认实现 — 充当 {@link BatchTaskExecutor} SPI 的路由器。
 *
 * <p>本类把 worker-core 派发来的 task 路由到对应原子任务 SPI:
 *
 * <ol>
 *   <li>执行器子类型:优先取 payload 里的 {@code taskType}(SPI 协议字段,如 shell/sql/stored_proc/http), 缺失时回退 {@code
 *       stepCode}(= job_task.task_type)。job_type=ATOMIC 的任务,task_type 也是 "ATOMIC", 真正的子类型只在 payload
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
public class DefaultStepExecutionAdapter implements StepExecutionAdapter {

  private final BatchTaskExecutorRegistry registry;
  private final TaskExecutionClient taskExecutionClient;

  @Autowired
  public DefaultStepExecutionAdapter(
      BatchTaskExecutorRegistry registry, TaskExecutionClient taskExecutionClient) {
    this.registry = registry;
    this.taskExecutionClient = taskExecutionClient;
  }

  DefaultStepExecutionAdapter(BatchTaskExecutorRegistry registry) {
    this(registry, null);
  }

  /** payload 里携带执行器子类型的字段名(SPI 协议)。 */
  private static final String PARAM_TASK_TYPE = "taskType";

  private static final String OUTPUT_ERROR_CODE = "error_code";

  @Override
  public ReplayCapabilityDeclaration resolveReplayCapability(StepExecutionRequest request) {
    Map<String, Object> parameters = extractParameters(request);
    String taskType = resolveExecutorType(request, parameters);
    return registry.replayCapability(taskType, parameters);
  }

  @Override
  public StepExecutionResponse execute(StepExecutionRequest request) {
    Map<String, Object> parameters = extractParameters(request);
    String taskType = resolveExecutorType(request, parameters);
    BatchTaskExecutor executor = registry.find(taskType);
    if (executor == null) {
      String safeTaskType = SwallowedExceptionLogger.safeMessage(taskType);
      log.warn(
          "no BatchTaskExecutor registered for taskType={}, registered={}",
          safeTaskType,
          registry.registeredTypes());
      return new StepExecutionResponse(
          false, "NO_EXECUTOR", "no BatchTaskExecutor registered for taskType=" + safeTaskType);
    }
    TaskContext ctx = new TaskContext(
        request.tenantId(),
        request.jobCode(),
        // taskInstanceId 暂不在 StepExecutionRequest 里;需要时从 context 抽
        null,
        request.workerId(),
        parameters,
        request.context());
    boolean executorIsSafeToReplay = executor.isSafeToReplay(ctx);
    if (!executorIsSafeToReplay && !markNonIdempotentStart(request)) {
      return new StepExecutionResponse(
          false,
          TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN,
          "non-idempotent execution fence was not confirmed; executor was not run");
    }
    try {
      TaskResult result = executor.execute(ctx);
      if (result.success() && !EmptyChecks.isEmpty(result.output())) {
        request.context().put(PipelineRuntimeKeys.NODE_OUTPUTS, result.output());
      }
      return toResponse(result);
    } catch (RuntimeException ex) {
      String failureSummary = SwallowedExceptionLogger.summary(ex);
      log.error(
          "BatchTaskExecutor uncaught exception, taskType={}, cause={}",
          LogSanitizer.value(taskType),
          failureSummary);
      return new StepExecutionResponse(false, "EXECUTOR_FAILURE", failureSummary);
    }
  }

  private boolean markNonIdempotentStart(StepExecutionRequest request) {
    if (EmptyChecks.isNull(taskExecutionClient)) {
      return false;
    }
    Map<String, Object> context = request.context();
    Long taskId =
        asLong(EmptyChecks.isNull(context) ? null : context.get(PipelineRuntimeKeys.TASK_ID));
    Object invocationValue = EmptyChecks.isNull(context)
        ? null
        : context.get(PipelineRuntimeKeys.PARTITION_INVOCATION_ID);
    if (EmptyChecks.isNull(taskId)
        || !(invocationValue instanceof String invocationId)
        || EmptyChecks.isBlank(invocationId)) {
      log.error(
          "non-idempotent task execution rejected because its persisted lease identity is absent");
      return false;
    }
    try {
      return taskExecutionClient.markNonIdempotentExecutionStarted(
          request.tenantId(), taskId, request.workerId(), invocationId);
    } catch (RuntimeException ex) {
      log.warn(
          "non-idempotent execution fence could not be confirmed; refusing executor call: {}",
          SwallowedExceptionLogger.summary(ex));
      return false;
    }
  }

  private static Long asLong(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof String text) {
      try {
        return Long.valueOf(text);
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  /** 解析 payload(default_params / launch 参数 JSON)为参数 Map;缺失或非法 JSON 对象时返回空 Map。 */
  private static Map<String, Object> extractParameters(StepExecutionRequest request) {
    Map<String, Object> context = request.context();
    Object payload = EmptyChecks.isNull(context) ? null : context.get(PipelineRuntimeKeys.PAYLOAD);
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
      log.warn(
          "Task payload is not a valid JSON object; executing with empty parameters: {}",
          SwallowedExceptionLogger.summary(ex));
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
    String code = "TASK_FAILED";
    Map<String, Object> output = result.output();
    Object reportedCode = output == null ? null : output.get(OUTPUT_ERROR_CODE);
    if (reportedCode instanceof String text && !text.isBlank()) {
      code = text.trim();
    }
    String message = EmptyChecks.isNull(result.error())
        ? SwallowedExceptionLogger.safeMessage(result.message())
        : SwallowedExceptionLogger.summary(result.error());
    return new StepExecutionResponse(false, code, message);
  }
}
