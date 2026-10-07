package io.github.pinpols.batch.orchestrator.application.service.task;

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
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

/** 将真实状态推进协作者的纯测试装配集中于此，不在生产入口保留另一套创建路径。 */
public final class TaskOutcomeServiceFixture {

  private TaskOutcomeServiceFixture() {}

  /** 测试依赖集合不参与 Spring 注册，也不作为生产服务的依赖容器。 */
  public record Dependencies(
      RetryGovernanceService retryGovernanceService,
      LifecycleEventMapper<Object> lifecycleEventMapper,
      WorkflowDagService workflowDagService,
      ObjectProvider<WorkflowNodeDispatchService> workflowNodeDispatchServiceProvider,
      WorkflowTerminalOutboxService workflowTerminalOutboxService,
      VerifierFailureOutboxService verifierFailureOutboxService,
      MeterRegistry meterRegistry,
      JobInstanceTerminalChildStateReconciler jobInstanceTerminalChildStateReconciler,
      ResultVersionWriter resultVersionWriter,
      BatchDayReplayTerminalReconciler batchDayReplayTerminalReconciler,
      FailureClassifier failureClassifier,
      JobLifecycleMetricsRecorder jobLifecycleMetricsRecorder,
      CountContinuityOutboxService countContinuityOutboxService,
      ApplicationEventPublisher applicationEventPublisher) {}

  public static DefaultTaskOutcomeService create(
      OrchestratorJobMappers jobMappers,
      OrchestratorWorkflowMappers workflowMappers,
      Dependencies dependencies) {
    TaskOutcomeNodeRunRecorder recorder = new TaskOutcomeNodeRunRecorder(workflowMappers);
    TaskOutcomeTerminalFinalizer finalizer = new TaskOutcomeTerminalFinalizer(
        dependencies.jobLifecycleMetricsRecorder(),
        dependencies.meterRegistry(),
        dependencies.jobInstanceTerminalChildStateReconciler(),
        dependencies.resultVersionWriter(),
        dependencies.batchDayReplayTerminalReconciler());
    TaskOutcomeDagProgressor dag = new TaskOutcomeDagProgressor(
        workflowMappers,
        dependencies.workflowDagService(),
        dependencies.workflowNodeDispatchServiceProvider(),
        recorder,
        dependencies.countContinuityOutboxService());
    TaskOutcomeWorkflowFinalizer workflow = new TaskOutcomeWorkflowFinalizer(
        workflowMappers,
        dependencies.lifecycleEventMapper(),
        dependencies.workflowTerminalOutboxService());
    TaskOutcomeInstanceProgressor progressor = new TaskOutcomeInstanceProgressor(
        jobMappers,
        workflowMappers,
        dependencies.lifecycleEventMapper(),
        dependencies.failureClassifier(),
        finalizer,
        dag,
        new TaskOutcomeParentTaskSignaler(),
        workflow);
    DefaultTaskOutcomeService service = new DefaultTaskOutcomeService(
        jobMappers,
        dependencies.retryGovernanceService(),
        dependencies.verifierFailureOutboxService(),
        dependencies.failureClassifier(),
        dependencies.countContinuityOutboxService(),
        dependencies.applicationEventPublisher(),
        dependencies.workflowNodeDispatchServiceProvider(),
        dependencies.meterRegistry(),
        recorder,
        progressor);
    service.initialize();
    return service;
  }
}
