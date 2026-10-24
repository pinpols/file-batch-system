package io.github.pinpols.batch.orchestrator.application.service.task;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

@DisplayName("Workflow 节点推进载荷")
class TaskOutcomeDagProgressorTest {

  @Test
  @DisplayName("下游节点使用启动当前节点时保存的源载荷，不继承子 Job 的执行参数")
  void advance_usesCapturedSourcePayloadForDownstreamDispatch() {
    WorkflowNodeRunMapper nodeRunMapper = mock(WorkflowNodeRunMapper.class);
    OrchestratorWorkflowMappers workflowMappers = new OrchestratorWorkflowMappers(
        mock(io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeMapper.class),
        mock(io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper.class),
        nodeRunMapper);
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
            org.mockito.ArgumentMatchers.any(),
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
    TaskOutcomeCommand successfulOutcome = TaskOutcomeCommand.builder().success(true).build();
    progressor.advance(TaskOutcomeDagProgressor.Context.builder()
        .command(successfulOutcome)
        .task(task)
        .jobInstance(jobInstance)
        .workflowRun(workflowRun)
        .currentNodeCode("IMPORT")
        .nodeProgress(new NodePartitionProgressCalculator.Result(1, 1, 0, 0, 0, Set.of(1L)))
        .nodeOutputs(Map.of())
        .activeNodes(new java.util.LinkedHashSet<>(Set.of("IMPORT")))
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
