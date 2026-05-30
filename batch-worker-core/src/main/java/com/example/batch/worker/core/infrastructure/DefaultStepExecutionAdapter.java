package com.example.batch.worker.core.infrastructure;

import com.example.batch.common.spi.task.BatchTaskExecutor;
import com.example.batch.common.spi.task.BatchTaskExecutorRegistry;
import com.example.batch.common.spi.task.TaskContext;
import com.example.batch.common.spi.task.TaskResult;
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
 * <p>P0 Phase 1 改造:本类从"no-op 兜底"升级为"按 {@code stepCode} 查 {@link BatchTaskExecutorRegistry}
 * 路由到对应原子任务 SPI"。
 *
 * <p>调用顺序(由 Spring {@code @Primary} 控制):
 *
 * <ol>
 *   <li>4 个 Pipeline worker module(import/export/process/dispatch)各自的 {@code
 *       XxxStepExecutionAdapter} 带 {@code @Primary},Spring 解析 {@link StepExecutionAdapter} 时优先选它们
 *   <li>本类作为 fallback,只在无 Pipeline 实现匹配时被注入(目前理论场景:worker-core 单独跑 / 仅注册原子任务的 worker 进程)
 * </ol>
 *
 * <p>实际生产中 4 个 worker module 都注册了 {@code @Primary},Pipeline 任务永远走 Pipeline 路径;原子任务的派发 (Shell / SQL
 * / HTTP / ...)由后续 PR 补 routing 改造让它能命中本类。设计见 {@code docs/design/task-spi-design.md} §4.4。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultStepExecutionAdapter implements StepExecutionAdapter {

  private final BatchTaskExecutorRegistry registry;

  @Override
  public StepExecutionResponse execute(StepExecutionRequest request) {
    String taskType = request.stepCode();
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
            // taskInstanceId — Phase 1 暂不在 StepExecutionRequest 里;后续 PR 加(从 runtime context 抽)
            null,
            request.workerId(),
            // parameters — Phase 1 暂传 empty;后续 PR 改 wrapper 从 EffectiveTaskConfig 抽
            Map.of(),
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

  private static StepExecutionResponse toResponse(TaskResult result) {
    if (result.success()) {
      return StepExecutionResponse.successResponse();
    }
    return new StepExecutionResponse(false, "TASK_FAILED", result.message());
  }
}
