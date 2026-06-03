package com.example.batch.worker.imports.infrastructure;

import com.example.batch.common.enums.JobType;
import com.example.batch.common.spi.task.BatchTaskExecutor;
import com.example.batch.common.spi.task.ResourceKind;
import com.example.batch.common.spi.task.TaskCapability;
import com.example.batch.common.spi.task.TaskContext;
import com.example.batch.common.spi.task.TaskResult;
import com.example.batch.worker.core.domain.StepExecutionRequest;
import com.example.batch.worker.core.domain.StepExecutionResponse;
import java.time.Duration;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Import pipeline 的 {@link BatchTaskExecutor} SPI 包装 — delegate 到现有 {@link
 * ImportStepExecutionAdapter}。
 *
 * <p>P0 Phase 3 改造(见 docs/design/task-spi-design.md §Phase 3):**包装,不替换**。
 *
 * <p>双路并存:
 *
 * <ul>
 *   <li>**老路径(主)**:Kafka task → DefaultTaskExecutionWrapper → ImportStepExecutionAdapter
 *       (@Primary)→ Pipeline lifecycle。100% 走原 4 个 worker module 的代码,行为不变
 *   <li>**新路径**:任意调用方拿 BatchTaskExecutorRegistry.find("IMPORT") → 本类 → 内部 delegate 到同一个
 *       ImportStepExecutionAdapter。给 Phase 4 (第三方 jar 调) + Phase 5 (多 taskType worker 打包) +
 *       console-api 拉 taskType 下拉框用
 * </ul>
 *
 * <p>**关键约束**:
 *
 * <ul>
 *   <li>不打 @Primary(否则 Spring 解析 {@link com.example.batch.worker.core.support.StepExecutionAdapter}
 *       时会有歧义 — 本类是包装而非 StepExecutionAdapter 实现)
 *   <li>不直接挑战 Pipeline 生命周期 — 完全 delegate
 *   <li>BatchTaskExecutorRegistry 启动期会校验 taskType 唯一,本类 taskType="IMPORT" 不能跟其他冲突
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ImportTaskExecutor implements BatchTaskExecutor {

  private final ImportStepExecutionAdapter delegate;

  @Override
  public String taskType() {
    return JobType.IMPORT
        .code(); // "IMPORT" — 跟 DefaultTaskExecutionWrapper 传的 task.getTaskType() 对齐
  }

  @Override
  public TaskCapability capability() {
    return new TaskCapability(
        Set.of(ResourceKind.DISK, ResourceKind.DB, ResourceKind.NET),
        false, // 文件 IMPORT 通常一次性,不视为幂等
        true,
        Duration.ofMinutes(30) // import 大文件可能跑久
        );
  }

  @Override
  public TaskResult execute(TaskContext ctx) {
    // SPI 路径:把 TaskContext 还原为 Pipeline 语义的 StepExecutionRequest
    // 注意:Pipeline 的 stepCode 历来 = task.getTaskType()(JobType code),不是某个具体 step
    // 见 DefaultTaskExecutionWrapper.execute() 的 new StepExecutionRequest(...) 第 3 参数
    StepExecutionRequest req =
        new StepExecutionRequest(
            ctx.tenantId(),
            ctx.jobCode(),
            taskType(), // stepCode = "IMPORT"
            ctx.workerId(),
            ctx.runtimeAttributes() // pipelineInstanceId / traceId / bizDate 等都在这
            );
    StepExecutionResponse resp = delegate.execute(req);
    if (resp.success()) {
      return TaskResult.ok(resp.message() == null ? "ok" : resp.message());
    }
    return TaskResult.fail(resp.message() == null ? resp.code() : resp.message());
  }
}
