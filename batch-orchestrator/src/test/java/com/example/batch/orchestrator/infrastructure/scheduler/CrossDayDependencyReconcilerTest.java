package com.example.batch.orchestrator.infrastructure.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.config.BatchTimezoneProperties;
import com.example.batch.common.config.BatchTimezoneProvider;
import com.example.batch.common.persistence.entity.WorkflowRunEntity;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.orchestrator.application.service.governance.AlertEventService;
import com.example.batch.orchestrator.application.service.task.OrchestratorJobMappers;
import com.example.batch.orchestrator.application.service.workflow.CrossDayDependencyResolver;
import com.example.batch.orchestrator.application.service.workflow.CrossDayDependencyResolver.ResolutionResult;
import com.example.batch.orchestrator.application.service.workflow.OrchestratorWorkflowMappers;
import com.example.batch.orchestrator.application.service.workflow.WorkflowDagService;
import com.example.batch.orchestrator.application.service.workflow.WorkflowNodeDispatchService;
import com.example.batch.orchestrator.config.CrossDayDependencyReconcileProperties;
import com.example.batch.orchestrator.controller.request.AlertEmitRequest;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import com.example.batch.orchestrator.domain.param.UpdateNodeRunStatusParam;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.WorkflowNodeMapper;
import com.example.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import com.example.batch.orchestrator.mapper.WorkflowRunMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class CrossDayDependencyReconcilerTest {

  private OrchestratorWorkflowMappers workflowMappers;
  private OrchestratorJobMappers jobMappers;
  private WorkflowNodeRunMapper nodeRunMapper;
  private WorkflowRunMapper runMapper;
  private WorkflowNodeMapper nodeMapper;
  private JobInstanceMapper jobInstanceMapper;
  private CrossDayDependencyResolver resolver;
  private WorkflowNodeDispatchService dispatchService;
  private AlertEventService alertEventService;
  private OrchestratorGracefulShutdown gracefulShutdown;
  private CrossDayDependencyReconcileProperties properties;
  private CrossDayDependencyReconciler reconciler;

  @BeforeEach
  void setUp() {
    nodeRunMapper = mock(WorkflowNodeRunMapper.class);
    runMapper = mock(WorkflowRunMapper.class);
    nodeMapper = mock(WorkflowNodeMapper.class);
    jobInstanceMapper = mock(JobInstanceMapper.class);
    workflowMappers = new OrchestratorWorkflowMappers(nodeMapper, runMapper, nodeRunMapper);
    jobMappers =
        new OrchestratorJobMappers(
            jobInstanceMapper,
            mock(com.example.batch.orchestrator.mapper.JobPartitionMapper.class),
            mock(com.example.batch.orchestrator.mapper.JobTaskMapper.class),
            mock(com.example.batch.orchestrator.mapper.JobStepInstanceMapper.class),
            mock(com.example.batch.orchestrator.mapper.TriggerRequestMapper.class));
    resolver = mock(CrossDayDependencyResolver.class);
    dispatchService = mock(WorkflowNodeDispatchService.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<WorkflowNodeDispatchService> provider = mock(ObjectProvider.class);
    when(provider.getObject()).thenReturn(dispatchService);
    alertEventService = mock(AlertEventService.class);
    gracefulShutdown = mock(OrchestratorGracefulShutdown.class);
    when(gracefulShutdown.isDraining()).thenReturn(false);
    properties = new CrossDayDependencyReconcileProperties();
    properties.setEnabled(true);
    properties.setBatchSize(50);
    properties.setDefaultTimeoutSeconds(86_400L);
    BatchDateTimeSupport dateTimeSupport =
        new BatchDateTimeSupport(
            Clock.systemUTC(), new BatchTimezoneProvider(new BatchTimezoneProperties()));
    reconciler =
        new CrossDayDependencyReconciler(
            workflowMappers,
            jobMappers,
            resolver,
            provider,
            alertEventService,
            properties,
            gracefulShutdown,
            dateTimeSupport);
  }

  @Test
  void disabledScheduleShortCircuits() {
    properties.setEnabled(false);
    reconciler.scheduledReconcile();
    verify(nodeRunMapper, never()).selectByNodeStatus(anyString(), anyInt());
  }

  @Test
  void drainingShutdownShortCircuits() {
    when(gracefulShutdown.isDraining()).thenReturn(true);
    reconciler.scheduledReconcile();
    verify(nodeRunMapper, never()).selectByNodeStatus(anyString(), anyInt());
  }

  @Test
  void resolvedDependencyTriggersDispatchNode() {
    WorkflowNodeRunEntity waiting =
        waitingNodeRun(101L, "AGG", "TASK", Instant.now().minusSeconds(60), 7L);
    WorkflowRunEntity workflowRun = workflowRun("t1", 7L, 200L, 333L);
    JobInstanceEntity jobInstance = jobInstance("t1", 333L, "AGG", LocalDate.of(2026, 5, 4));
    WorkflowNodeEntity node = workflowNode(200L, "AGG", "TASK", "[{\"jobCode\":\"X\"}]");

    when(nodeRunMapper.selectByNodeStatus(eq("WAITING_DEPENDENCY"), anyInt()))
        .thenReturn(List.of(waiting));
    when(runMapper.selectByIdAnyTenant(7L)).thenReturn(workflowRun);
    when(jobInstanceMapper.selectById("t1", 333L)).thenReturn(jobInstance);
    when(nodeMapper.selectByWorkflowDefinitionIdAndNodeCode(200L, "AGG")).thenReturn(node);
    when(resolver.resolve(eq("t1"), eq(LocalDate.of(2026, 5, 4)), anyString()))
        .thenReturn(
            ResolutionResult.builder()
                .status(CrossDayDependencyResolver.ResolutionStatus.RESOLVED)
                .resolved(Map.of("alias", Map.of("k", "v")))
                .build());

    reconciler.scheduledReconcile();

    verify(dispatchService)
        .dispatchNode(
            eq(jobInstance),
            eq(workflowRun),
            any(WorkflowDagService.DagNodeResolution.class),
            any(),
            any());
    verify(nodeRunMapper, never()).updateStatus(any(UpdateNodeRunStatusParam.class));
  }

  @Test
  void waitingTimeoutMarksNodeFailedAndAlerts() {
    Instant farPast = Instant.now().minusSeconds(100_000L);
    WorkflowNodeRunEntity waiting = waitingNodeRun(102L, "AGG", "TASK", farPast, 8L);
    WorkflowRunEntity workflowRun = workflowRun("t1", 8L, 200L, 334L);
    JobInstanceEntity jobInstance = jobInstance("t1", 334L, "AGG", LocalDate.of(2026, 5, 4));
    WorkflowNodeEntity node = workflowNode(200L, "AGG", "TASK", "[{\"jobCode\":\"X\"}]");
    node.setCrossDayDependencyTimeoutSeconds(60);

    when(nodeRunMapper.selectByNodeStatus(eq("WAITING_DEPENDENCY"), anyInt()))
        .thenReturn(List.of(waiting));
    when(runMapper.selectByIdAnyTenant(8L)).thenReturn(workflowRun);
    when(jobInstanceMapper.selectById("t1", 334L)).thenReturn(jobInstance);
    when(nodeMapper.selectByWorkflowDefinitionIdAndNodeCode(200L, "AGG")).thenReturn(node);
    when(resolver.resolve(eq("t1"), any(LocalDate.class), anyString()))
        .thenReturn(
            ResolutionResult.builder()
                .status(CrossDayDependencyResolver.ResolutionStatus.WAITING)
                .resolved(Map.of())
                .waitingReasons(List.of("MISSING:alias=t_minus_1"))
                .build());

    reconciler.scheduledReconcile();

    verify(nodeRunMapper).updateStatus(any(UpdateNodeRunStatusParam.class));
    verify(alertEventService, atLeastOnce()).emit(any(AlertEmitRequest.class));
    verify(dispatchService, never()).dispatchNode(any(), any(), any(), any(), any());
  }

  @Test
  void specRemovedSinceWaitingMarksFailed() {
    WorkflowNodeRunEntity waiting =
        waitingNodeRun(103L, "AGG", "TASK", Instant.now().minusSeconds(30), 9L);
    WorkflowRunEntity workflowRun = workflowRun("t1", 9L, 200L, 335L);
    JobInstanceEntity jobInstance = jobInstance("t1", 335L, "AGG", LocalDate.of(2026, 5, 4));
    WorkflowNodeEntity node = workflowNode(200L, "AGG", "TASK", null);

    when(nodeRunMapper.selectByNodeStatus(eq("WAITING_DEPENDENCY"), anyInt()))
        .thenReturn(List.of(waiting));
    when(runMapper.selectByIdAnyTenant(9L)).thenReturn(workflowRun);
    when(jobInstanceMapper.selectById("t1", 335L)).thenReturn(jobInstance);
    when(nodeMapper.selectByWorkflowDefinitionIdAndNodeCode(200L, "AGG")).thenReturn(node);

    reconciler.scheduledReconcile();

    verify(nodeRunMapper).updateStatus(any(UpdateNodeRunStatusParam.class));
    verify(resolver, never()).resolve(anyString(), any(), anyString());
  }

  @Test
  void waitingWithinTimeoutWindowDoesNotChangeAnything() {
    WorkflowNodeRunEntity waiting =
        waitingNodeRun(104L, "AGG", "TASK", Instant.now().minusSeconds(10), 10L);
    WorkflowRunEntity workflowRun = workflowRun("t1", 10L, 200L, 336L);
    JobInstanceEntity jobInstance = jobInstance("t1", 336L, "AGG", LocalDate.of(2026, 5, 4));
    WorkflowNodeEntity node = workflowNode(200L, "AGG", "TASK", "[{\"jobCode\":\"X\"}]");
    node.setCrossDayDependencyTimeoutSeconds(86_400);

    when(nodeRunMapper.selectByNodeStatus(eq("WAITING_DEPENDENCY"), anyInt()))
        .thenReturn(List.of(waiting));
    when(runMapper.selectByIdAnyTenant(10L)).thenReturn(workflowRun);
    when(jobInstanceMapper.selectById("t1", 336L)).thenReturn(jobInstance);
    when(nodeMapper.selectByWorkflowDefinitionIdAndNodeCode(200L, "AGG")).thenReturn(node);
    when(resolver.resolve(eq("t1"), any(LocalDate.class), anyString()))
        .thenReturn(
            ResolutionResult.builder()
                .status(CrossDayDependencyResolver.ResolutionStatus.WAITING)
                .resolved(Map.of())
                .waitingReasons(List.of("MISSING:alias=t_minus_1"))
                .build());

    reconciler.scheduledReconcile();

    verify(nodeRunMapper, never()).updateStatus(any(UpdateNodeRunStatusParam.class));
    verify(alertEventService, never()).emit(any(AlertEmitRequest.class));
  }

  @Test
  void resolverFailureMarksNodeFailedAndAlerts() {
    WorkflowNodeRunEntity waiting =
        waitingNodeRun(105L, "AGG", "TASK", Instant.now().minusSeconds(30), 11L);
    WorkflowRunEntity workflowRun = workflowRun("t1", 11L, 200L, 337L);
    JobInstanceEntity jobInstance = jobInstance("t1", 337L, "AGG", LocalDate.of(2026, 5, 4));
    WorkflowNodeEntity node = workflowNode(200L, "AGG", "TASK", "[{\"jobCode\":\"X\"}]");

    when(nodeRunMapper.selectByNodeStatus(eq("WAITING_DEPENDENCY"), anyInt()))
        .thenReturn(List.of(waiting));
    when(runMapper.selectByIdAnyTenant(11L)).thenReturn(workflowRun);
    when(jobInstanceMapper.selectById("t1", 337L)).thenReturn(jobInstance);
    when(nodeMapper.selectByWorkflowDefinitionIdAndNodeCode(200L, "AGG")).thenReturn(node);
    when(resolver.resolve(eq("t1"), any(LocalDate.class), anyString()))
        .thenReturn(
            ResolutionResult.builder()
                .status(CrossDayDependencyResolver.ResolutionStatus.FAILED)
                .resolved(Map.of())
                .failureCode("CROSS_DAY_DEP_INVALID_SPEC")
                .build());

    reconciler.scheduledReconcile();

    verify(nodeRunMapper).updateStatus(any(UpdateNodeRunStatusParam.class));
    verify(alertEventService).emit(any(AlertEmitRequest.class));
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private static WorkflowNodeRunEntity waitingNodeRun(
      Long id, String nodeCode, String nodeType, Instant startedAt, Long workflowRunId) {
    WorkflowNodeRunEntity entity = new WorkflowNodeRunEntity();
    entity.setId(id);
    entity.setWorkflowRunId(workflowRunId);
    entity.setNodeCode(nodeCode);
    entity.setNodeType(nodeType);
    entity.setRunSeq(1);
    entity.setNodeStatus("WAITING_DEPENDENCY");
    entity.setStartedAt(startedAt);
    entity.setErrorCode("CROSS_DAY_DEP_WAITING");
    entity.setErrorMessage("MISSING:alias=t_minus_1");
    return entity;
  }

  private static WorkflowRunEntity workflowRun(
      String tenantId, Long id, Long workflowDefinitionId, Long relatedJobInstanceId) {
    WorkflowRunEntity entity = new WorkflowRunEntity();
    entity.setId(id);
    entity.setTenantId(tenantId);
    entity.setWorkflowDefinitionId(workflowDefinitionId);
    entity.setRelatedJobInstanceId(relatedJobInstanceId);
    entity.setBizDate(LocalDate.of(2026, 5, 4));
    entity.setRunStatus("RUNNING");
    return entity;
  }

  private static JobInstanceEntity jobInstance(
      String tenantId, Long id, String jobCode, LocalDate bizDate) {
    JobInstanceEntity entity = new JobInstanceEntity();
    entity.setId(id);
    entity.setTenantId(tenantId);
    entity.setJobCode(jobCode);
    entity.setBizDate(bizDate);
    entity.setInstanceStatus("RUNNING");
    return entity;
  }

  private static WorkflowNodeEntity workflowNode(
      Long workflowDefinitionId, String nodeCode, String nodeType, String crossDayDeps) {
    WorkflowNodeEntity entity = new WorkflowNodeEntity();
    entity.setWorkflowDefinitionId(workflowDefinitionId);
    entity.setNodeCode(nodeCode);
    entity.setNodeType(nodeType);
    entity.setCrossDayDependencies(crossDayDeps);
    return entity;
  }
}
