package io.github.pinpols.batch.orchestrator.application.service.task;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.WorkflowRunStatus;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.orchestrator.application.engine.CountContinuityOutboxService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.OrchestratorWorkflowMappers;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowDagService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowNodeDispatchService;
import io.github.pinpols.batch.orchestrator.domain.command.TaskOutcomeCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateNodeRunStatusParam;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.ObjectProvider;

@DisplayName("Workflow 节点推进载荷")
class TaskOutcomeDagProgressorTest {

  @Test
  @DisplayName("PAUSED 时记录在途节点终态但不完成 END、不派发或级联跳过下游")
  void advance_recordsInflightOutcomeWithoutProgressing_whenWorkflowPaused() {
    WorkflowNodeRunMapper nodeRunMapper = mock(WorkflowNodeRunMapper.class);
    OrchestratorWorkflowMappers workflowMappers = new OrchestratorWorkflowMappers(
        mock(WorkflowNodeMapper.class), mock(WorkflowRunMapper.class), nodeRunMapper);
    WorkflowNodeRunEntity currentNodeRun = new WorkflowNodeRunEntity();
    currentNodeRun.setId(7001L);
    currentNodeRun.setWorkflowRunId(700L);
    currentNodeRun.setNodeCode("IMPORT");
    currentNodeRun.setNodeType("JOB");
    currentNodeRun.setRunSeq(1);
    currentNodeRun.setNodeStatus("RUNNING");
    currentNodeRun.setStartedAt(Instant.EPOCH);
    when(nodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(700L, "IMPORT"))
        .thenReturn(currentNodeRun);
    when(nodeRunMapper.selectLatestForUpdate(700L, "IMPORT")).thenReturn(currentNodeRun);

    WorkflowDagService dagService = mock(WorkflowDagService.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<WorkflowNodeDispatchService> dispatchProvider = mock(ObjectProvider.class);
    CountContinuityOutboxService continuityOutbox = mock(CountContinuityOutboxService.class);
    TaskOutcomeDagProgressor progressor = new TaskOutcomeDagProgressor(
        workflowMappers,
        dagService,
        dispatchProvider,
        new TaskOutcomeNodeRunRecorder(workflowMappers),
        continuityOutbox,
        mock(SecretPayloadProtector.class));

    WorkflowRunEntity workflowRun = new WorkflowRunEntity();
    workflowRun.setId(700L);
    workflowRun.setRunStatus(WorkflowRunStatus.PAUSED.code());
    JobTaskEntity task = new JobTaskEntity();
    task.setTaskPayload("{\"workflowNodeCode\":\"IMPORT\",\"workflowNodeType\":\"JOB\"}");
    JobInstanceEntity jobInstance = new JobInstanceEntity();
    jobInstance.setTraceId("trace-700");

    TaskOutcomeCommand command = TaskOutcomeCommand.builder().success(true).build();
    progressor.advance(TaskOutcomeDagProgressor.Context.builder()
        .command(command)
        .task(task)
        .jobInstance(jobInstance)
        .workflowRun(workflowRun)
        .currentNodeCode("IMPORT")
        .nodeProgress(new NodePartitionProgressCalculator.Result(1, 1, 0, 0, 0, Set.of(1L)))
        .nodeOutputs(Map.of())
        .activeNodes(new LinkedHashSet<>(Set.of("IMPORT")))
        .finishedAt(Instant.EPOCH)
        .build());

    verify(nodeRunMapper).updateStatus(ArgumentMatchers.any(UpdateNodeRunStatusParam.class));
    verifyNoInteractions(dagService, dispatchProvider);
    verify(continuityOutbox).checkContinuity(700L, "IMPORT", null);
  }

  @Test
  @DisplayName("下游节点使用启动当前节点时保存的源载荷，不继承子 Job 的执行参数")
  void advance_usesCapturedSourcePayloadForDownstreamDispatch() {
    WorkflowNodeRunMapper nodeRunMapper = mock(WorkflowNodeRunMapper.class);
    OrchestratorWorkflowMappers workflowMappers = new OrchestratorWorkflowMappers(
        mock(WorkflowNodeMapper.class), mock(WorkflowRunMapper.class), nodeRunMapper);
    WorkflowNodeRunEntity currentNodeRun = new WorkflowNodeRunEntity();
    currentNodeRun.setSourcePayloadProtected("protected-root-payload");
    currentNodeRun.setStartedAt(Instant.EPOCH);
    when(nodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(700L, "IMPORT"))
        .thenReturn(currentNodeRun);
    when(nodeRunMapper.selectLatestForUpdate(700L, "IMPORT")).thenReturn(currentNodeRun);

    SecretPayloadProtector payloadProtector = mock(SecretPayloadProtector.class);
    String rootPayload = "{\"batchNo\":\"root-batch\"}";
    when(payloadProtector.reveal("protected-root-payload")).thenReturn(rootPayload);

    WorkflowDagService dagService = mock(WorkflowDagService.class);
    WorkflowRunEntity workflowRun = new WorkflowRunEntity();
    workflowRun.setId(700L);
    workflowRun.setWorkflowDefinitionId(701L);
    when(dagService.resolveNextNodesForRun(workflowRun, "IMPORT", true, rootPayload))
        .thenReturn(List.of(new WorkflowDagService.DagNodeResolution("EXPORT", "JOB")));

    @SuppressWarnings("unchecked")
    ObjectProvider<WorkflowNodeDispatchService> dispatchProvider = mock(ObjectProvider.class);
    WorkflowNodeDispatchService dispatchService = mock(WorkflowNodeDispatchService.class);
    when(dispatchProvider.getObject()).thenReturn(dispatchService);
    when(dispatchService.dispatchNode(
            ArgumentMatchers.any(),
            eq(workflowRun),
            eq(new WorkflowDagService.DagNodeResolution("EXPORT", "JOB")),
            eq(rootPayload),
            eq("trace-700")))
        .thenReturn(0);

    TaskOutcomeNodeRunRecorder nodeRunRecorder = new TaskOutcomeNodeRunRecorder(workflowMappers);
    CountContinuityOutboxService continuityOutbox = mock(CountContinuityOutboxService.class);
    TaskOutcomeDagProgressor progressor = new TaskOutcomeDagProgressor(
        workflowMappers,
        dagService,
        dispatchProvider,
        nodeRunRecorder,
        continuityOutbox,
        payloadProtector);

    JobTaskEntity task = new JobTaskEntity();
    task.setTaskPayload("{\"workflowNodeCode\":\"IMPORT\",\"workflowNodeType\":\"JOB\","
        + "\"templateCode\":\"ta_import_customer_tpl\"}");
    JobInstanceEntity jobInstance = new JobInstanceEntity();
    jobInstance.setTraceId("trace-700");
    TaskOutcomeCommand successfulOutcome =
        TaskOutcomeCommand.builder().success(true).build();
    progressor.advance(TaskOutcomeDagProgressor.Context.builder()
        .command(successfulOutcome)
        .task(task)
        .jobInstance(jobInstance)
        .workflowRun(workflowRun)
        .currentNodeCode("IMPORT")
        .nodeProgress(new NodePartitionProgressCalculator.Result(1, 1, 0, 0, 0, Set.of(1L)))
        .nodeOutputs(Map.of())
        .activeNodes(new LinkedHashSet<>(Set.of("IMPORT")))
        .finishedAt(Instant.EPOCH)
        .build());

    verify(dagService).resolveNextNodesForRun(workflowRun, "IMPORT", true, rootPayload);
    verify(dispatchService)
        .dispatchNode(
            jobInstance,
            workflowRun,
            new WorkflowDagService.DagNodeResolution("EXPORT", "JOB"),
            rootPayload,
            "trace-700");
  }
}
