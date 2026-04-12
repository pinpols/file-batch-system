package com.example.batch.orchestrator.infrastructure.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.model.WorkerRouteModel;
import com.example.batch.orchestrator.application.route.WorkerRouter;
import com.example.batch.orchestrator.domain.pipeline.ExecutionContext;
import com.example.batch.orchestrator.domain.pipeline.PipelineDefinition;
import com.example.batch.orchestrator.domain.pipeline.PipelineExecutionResult;
import com.example.batch.orchestrator.domain.pipeline.Step;
import com.example.batch.orchestrator.domain.pipeline.StepDefinition;
import com.example.batch.orchestrator.domain.pipeline.StepRegistry;
import com.example.batch.orchestrator.domain.pipeline.StepResult;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultPipelineExecutorTest {

  private StepRegistry stepRegistry;
  private WorkerRouter workerRouter;
  private DefaultPipelineExecutor executor;

  @BeforeEach
  void setUp() {
    stepRegistry = mock(StepRegistry.class);
    workerRouter = mock(WorkerRouter.class);
    executor = new DefaultPipelineExecutor(stepRegistry, workerRouter);
  }

  @Test
  void shouldReturnEmptyResultWhenDefinitionIsNull() {
    ExecutionContext context = new ExecutionContext();
    context.setPipelineDefinition(null);

    PipelineExecutionResult result = executor.execute(context);

    assertThat(result).isNotNull();
    verify(stepRegistry, never()).find(anyString());
  }

  @Test
  void shouldReturnEmptyResultWhenStepsIsNull() {
    ExecutionContext context = contextWithSteps(null);

    PipelineExecutionResult result = executor.execute(context);

    assertThat(result).isNotNull();
    verify(stepRegistry, never()).find(anyString());
  }

  @Test
  void shouldSkipDisabledSteps() {
    StepDefinition disabled = stepDef("DISABLED", 1, false, null);
    ExecutionContext context = contextWithSteps(List.of(disabled));

    executor.execute(context);

    verify(stepRegistry, never()).find(anyString());
  }

  @Test
  void shouldExecuteEnabledStepsInOrder() {
    StepDefinition s1 = stepDef("S1", 2, true, null);
    StepDefinition s2 = stepDef("S2", 1, true, null);

    Step mockStep = mock(Step.class);
    when(mockStep.execute(any(), any())).thenReturn(new StepResult());
    when(stepRegistry.find(anyString())).thenReturn(Optional.of(mockStep));

    WorkerRouteModel route = new WorkerRouteModel();
    route.setWorkerType("IMPORT");
    when(workerRouter.route(anyString(), anyString(), anyString())).thenReturn(route);

    ExecutionContext context = contextWithSteps(List.of(s1, s2));

    executor.execute(context);

    verify(stepRegistry, times(2)).find(anyString());
    // S2（order=1）和 S1（order=2）都应被执行
    verify(stepRegistry).find("S2");
    verify(stepRegistry).find("S1");
  }

  @Test
  void shouldSkipExecutionWhenStepNotFoundInRegistry() {
    StepDefinition s1 = stepDef("UNKNOWN", 1, true, null);
    when(stepRegistry.find("UNKNOWN")).thenReturn(Optional.empty());

    ExecutionContext context = contextWithSteps(List.of(s1));
    // 不应抛出异常；结果应非空
    PipelineExecutionResult result = executor.execute(context);
    assertThat(result).isNotNull();
  }

  @Test
  void shouldOverrideWorkerTypeFromStepDefinition() {
    StepDefinition s1 = stepDef("S1", 1, true, "EXPORT");
    s1.setCapabilityTags(Set.of("HIGH_MEM"));
    s1.setResourceProfile("large");

    WorkerRouteModel defaultRoute = new WorkerRouteModel();
    defaultRoute.setWorkerType("IMPORT");
    defaultRoute.setPriority(5);

    Step mockStep = mock(Step.class);
    when(mockStep.execute(any(), any()))
        .thenAnswer(
            invocation -> {
              WorkerRouteModel usedRoute = invocation.getArgument(1);
              // the step-level workerType should override default
              assertThat(usedRoute.getWorkerType()).isEqualTo("EXPORT");
              assertThat(usedRoute.getCapabilityTags()).contains("HIGH_MEM");
              assertThat(usedRoute.getResourceProfile()).isEqualTo("large");
              return new StepResult();
            });
    when(stepRegistry.find("S1")).thenReturn(Optional.of(mockStep));

    ExecutionContext context = contextWithSteps(List.of(s1));
    context.setDefaultWorkerRoute(defaultRoute);

    executor.execute(context);
  }

  @Test
  void shouldUseDefaultWorkerRouteWhenStepHasNoWorkerTypeOverride() {
    StepDefinition s1 = stepDef("S1", 1, true, null);

    WorkerRouteModel defaultRoute = new WorkerRouteModel();
    defaultRoute.setWorkerType("IMPORT");

    Step mockStep = mock(Step.class);
    when(mockStep.execute(any(), any()))
        .thenAnswer(
            invocation -> {
              WorkerRouteModel usedRoute = invocation.getArgument(1);
              assertThat(usedRoute.getWorkerType()).isEqualTo("IMPORT");
              return new StepResult();
            });
    when(stepRegistry.find("S1")).thenReturn(Optional.of(mockStep));

    ExecutionContext context = contextWithSteps(List.of(s1));
    context.setDefaultWorkerRoute(defaultRoute);

    executor.execute(context);
    // workerRouter.route should NOT be called since defaultWorkerRoute is set
    verify(workerRouter, never()).route(anyString(), anyString(), anyString());
  }

  @Test
  void shouldRouteViaWorkerRouterWhenNoDefaultRouteSet() {
    StepDefinition s1 = stepDef("S1", 1, true, null);

    WorkerRouteModel routed = new WorkerRouteModel();
    routed.setWorkerType("DISPATCH");
    when(workerRouter.route(anyString(), anyString(), anyString())).thenReturn(routed);

    Step mockStep = mock(Step.class);
    when(mockStep.execute(any(), any()))
        .thenAnswer(
            invocation -> {
              WorkerRouteModel usedRoute = invocation.getArgument(1);
              assertThat(usedRoute.getWorkerType()).isEqualTo("DISPATCH");
              return new StepResult();
            });
    when(stepRegistry.find("S1")).thenReturn(Optional.of(mockStep));

    ExecutionContext context = contextWithSteps(List.of(s1));
    // defaultWorkerRoute is null

    executor.execute(context);

    verify(workerRouter).route(any(), anyString(), eq("S1"));
  }

  // --- helpers ---

  private static ExecutionContext contextWithSteps(List<StepDefinition> steps) {
    PipelineDefinition definition = new PipelineDefinition();
    definition.setJobCode("TEST_PIPELINE");
    definition.setSteps(steps);

    ExecutionContext context = new ExecutionContext();
    context.setTenantId("t1");
    context.setPipelineDefinition(definition);
    return context;
  }

  private static StepDefinition stepDef(
      String code, int order, boolean enabled, String workerType) {
    StepDefinition step = new StepDefinition();
    step.setStepCode(code);
    step.setStepOrder(order);
    step.setEnabled(enabled);
    step.setWorkerType(workerType);
    return step;
  }
}
