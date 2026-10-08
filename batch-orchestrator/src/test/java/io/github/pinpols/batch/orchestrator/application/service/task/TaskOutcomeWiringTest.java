package io.github.pinpols.batch.orchestrator.application.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.pinpols.batch.orchestrator.application.engine.CountContinuityOutboxService;
import io.github.pinpols.batch.orchestrator.application.engine.VerifierFailureOutboxService;
import io.github.pinpols.batch.orchestrator.application.engine.WorkflowTerminalOutboxService;
import io.github.pinpols.batch.orchestrator.application.service.governance.RetryGovernanceService;
import io.github.pinpols.batch.orchestrator.application.service.replay.BatchDayReplayTerminalReconciler;
import io.github.pinpols.batch.orchestrator.application.service.version.ResultVersionWriter;
import io.github.pinpols.batch.orchestrator.application.service.workflow.OrchestratorWorkflowMappers;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowDagService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowNodeDispatchService;
import io.github.pinpols.batch.orchestrator.domain.statemachine.LifecycleEventMapper;
import io.github.pinpols.batch.orchestrator.observability.JobLifecycleMetricsRecorder;
import io.github.pinpols.batch.orchestrator.service.failure.FailureClassifier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("任务结果生产装配：直接注入窄协作者并在容器生命周期注册指标")
class TaskOutcomeWiringTest {

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withUserConfiguration(
          DefaultTaskOutcomeService.class,
          TaskOutcomeInstanceProgressor.class,
          TaskOutcomeNodeRunRecorder.class,
          TaskOutcomeTerminalFinalizer.class,
          TaskOutcomeDagProgressor.class,
          TaskOutcomeParentTaskSignaler.class,
          TaskOutcomeWorkflowFinalizer.class)
      .withBean(OrchestratorJobMappers.class, () -> mock(OrchestratorJobMappers.class))
      .withBean(OrchestratorWorkflowMappers.class, () -> mock(OrchestratorWorkflowMappers.class))
      .withBean(RetryGovernanceService.class, () -> mock(RetryGovernanceService.class))
      .withBean(VerifierFailureOutboxService.class, () -> mock(VerifierFailureOutboxService.class))
      .withBean(CountContinuityOutboxService.class, () -> mock(CountContinuityOutboxService.class))
      .withBean(FailureClassifier.class, () -> mock(FailureClassifier.class))
      .withBean(LifecycleEventMapper.class, () -> mock(LifecycleEventMapper.class))
      .withBean(
          WorkflowTerminalOutboxService.class, () -> mock(WorkflowTerminalOutboxService.class))
      .withBean(WorkflowDagService.class, () -> mock(WorkflowDagService.class))
      .withBean(
          JobInstanceTerminalChildStateReconciler.class,
          () -> mock(JobInstanceTerminalChildStateReconciler.class))
      .withBean(ResultVersionWriter.class, () -> mock(ResultVersionWriter.class))
      .withBean(
          BatchDayReplayTerminalReconciler.class,
          () -> mock(BatchDayReplayTerminalReconciler.class))
      .withBean(JobLifecycleMetricsRecorder.class, () -> mock(JobLifecycleMetricsRecorder.class))
      .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new);

  @Test
  @DisplayName("生产构造器唯一且容器初始化指标，入口使用真实注入的推进协作者")
  void shouldWireRealEntryAndProgressor_whenDependenciesAreAvailable() {
    runner
        .withBean(WorkflowNodeDispatchService.class, () -> mock(WorkflowNodeDispatchService.class))
        .run(context -> {
          assertThat(context)
              .hasNotFailed()
              .hasSingleBean(DefaultTaskOutcomeService.class)
              .hasSingleBean(TaskOutcomeInstanceProgressor.class);
          assertThat(DefaultTaskOutcomeService.class.getConstructors()).hasSize(1);
          SimpleMeterRegistry registry = context.getBean(SimpleMeterRegistry.class);
          assertThat(registry.find("batch.orchestrator.cas.miss").counter()).isNotNull();
          assertThat(registry.find("batch.report.advisory_lock.wait").timer()).isNotNull();
          assertThat(context.getBean(DefaultTaskOutcomeService.class))
              .extracting("nodeRunRecorder", "instanceProgressor")
              .containsExactly(
                  context.getBean(TaskOutcomeNodeRunRecorder.class),
                  context.getBean(TaskOutcomeInstanceProgressor.class));
        });
  }

  @Test
  @DisplayName("惰性派发服务不能建立时仍在启动阶段失败，不延迟到首个回报")
  void shouldFailStartup_whenLazyDispatchCannotBeCreated() {
    runner
        .withBean(WorkflowNodeDispatchService.class, () -> {
          throw new IllegalStateException("dispatch unavailable");
        })
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure()).hasRootCauseMessage("dispatch unavailable");
        });
  }
}
