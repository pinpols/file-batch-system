package com.example.batch.orchestrator.infrastructure.pipeline;

import com.example.batch.common.model.WorkerRouteModel;
import com.example.batch.orchestrator.application.route.WorkerRouter;
import com.example.batch.orchestrator.domain.pipeline.ExecutionContext;
import com.example.batch.orchestrator.domain.pipeline.PipelineDefinition;
import com.example.batch.orchestrator.domain.pipeline.PipelineExecutionResult;
import com.example.batch.orchestrator.domain.pipeline.PipelineExecutor;
import com.example.batch.orchestrator.domain.pipeline.Step;
import com.example.batch.orchestrator.domain.pipeline.StepDefinition;
import com.example.batch.orchestrator.domain.pipeline.StepRegistry;
import com.example.batch.orchestrator.domain.pipeline.StepResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 默认流水线执行器。
 *
 * <p>按 {@code stepOrder} 升序依次执行 {@link PipelineDefinition} 中已启用的步骤，
 * 每步通过 {@link StepRegistry} 查找对应的 {@link Step} 实现并传入 {@link ExecutionContext} 驱动执行。
 * Worker 路由优先使用 {@code context.defaultWorkerRoute}；若步骤定义覆盖了 {@code workerType}，
 * 则克隆路由对象并替换类型与能力标签，避免修改共享引用。执行结果汇总为
 * {@link PipelineExecutionResult} 并写回上下文的 {@code stepResults}。
 * 若 {@code PipelineDefinition} 或其步骤列表为 {@code null}，直接返回空结果。
 */
@Component
@RequiredArgsConstructor
public class DefaultPipelineExecutor implements PipelineExecutor {

  private final StepRegistry stepRegistry;
  private final WorkerRouter workerRouter;

  @Override
  public PipelineExecutionResult execute(ExecutionContext context) {
    PipelineDefinition definition = context.getPipelineDefinition();
    List<StepResult> results = new ArrayList<>();
    if (definition == null || definition.getSteps() == null) {
      return emptyResult();
    }
    definition.getSteps().stream()
        .filter(step -> Boolean.TRUE.equals(step.getEnabled()))
        .sorted(
            Comparator.comparing(
                step -> step.getStepOrder() == null ? Integer.MAX_VALUE : step.getStepOrder()))
        .forEach(stepDefinition -> results.add(executeStep(context, definition, stepDefinition)));
    context.setStepResults(results);
    PipelineExecutionResult result = new PipelineExecutionResult();
    result.setStepResults(results);
    return result;
  }

  private StepResult executeStep(
      ExecutionContext context, PipelineDefinition definition, StepDefinition stepDefinition) {
    WorkerRouteModel workerRouteModel = resolveWorkerRoute(context, definition, stepDefinition);
    Optional<Step> step = stepRegistry.find(stepDefinition.getStepCode());
    if (step.isEmpty()) {
      return new StepResult();
    }
    return step.get().execute(context, workerRouteModel);
  }

  private WorkerRouteModel resolveWorkerRoute(
      ExecutionContext context, PipelineDefinition definition, StepDefinition stepDefinition) {
    WorkerRouteModel route = context.getDefaultWorkerRoute();
    if (route == null) {
      route =
          workerRouter.route(
              context.getTenantId(), definition.getJobCode(), stepDefinition.getStepCode());
    }
    if (route == null) {
      route = new WorkerRouteModel();
    }
    if (stepDefinition.getWorkerType() != null && !stepDefinition.getWorkerType().isBlank()) {
      WorkerRouteModel override = copyRoute(route);
      override.setWorkerType(stepDefinition.getWorkerType());
      override.setCapabilityTags(stepDefinition.getCapabilityTags());
      override.setResourceProfile(stepDefinition.getResourceProfile());
      return override;
    }
    return route;
  }

  private WorkerRouteModel copyRoute(WorkerRouteModel source) {
    WorkerRouteModel target = new WorkerRouteModel();
    target.setWorkerCode(source.getWorkerCode());
    target.setWorkerType(source.getWorkerType());
    target.setCapabilityTags(source.getCapabilityTags());
    target.setResourceProfile(source.getResourceProfile());
    target.setPriority(source.getPriority());
    target.setAvailable(source.getAvailable());
    return target;
  }

  private PipelineExecutionResult emptyResult() {
    return new PipelineExecutionResult();
  }
}
