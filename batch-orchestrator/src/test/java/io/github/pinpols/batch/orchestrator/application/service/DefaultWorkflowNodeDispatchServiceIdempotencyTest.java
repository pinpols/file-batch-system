package io.github.pinpols.batch.orchestrator.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.orchestrator.application.engine.TaskDispatchOutboxService;
import io.github.pinpols.batch.orchestrator.application.plan.SchedulePlanBuilder;
import io.github.pinpols.batch.orchestrator.application.scheduler.ResourceScheduler;
import io.github.pinpols.batch.orchestrator.application.service.task.ChildJobLaunchSupport;
import io.github.pinpols.batch.orchestrator.application.service.task.OrchestratorJobMappers;
import io.github.pinpols.batch.orchestrator.application.service.task.PartitionLifecycleService;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskExecutionService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.CrossDayDependencyResolver;
import io.github.pinpols.batch.orchestrator.application.service.workflow.DefaultWorkflowNodeDispatchService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.OrchestratorWorkflowMappers;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowDagService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowNodePayloadBuilder;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobStepInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class DefaultWorkflowNodeDispatchServiceIdempotencyTest {

  @Mock JobInstanceMapper jobInstanceMapper;
  @Mock JobPartitionMapper jobPartitionMapper;
  @Mock JobTaskMapper jobTaskMapper;
  @Mock JobStepInstanceMapper jobStepInstanceMapper;
  @Mock TriggerRequestMapper triggerRequestMapper;
  @Mock WorkflowNodeMapper workflowNodeMapper;
  @Mock WorkflowRunMapper workflowRunMapper;
  @Mock WorkflowNodeRunMapper workflowNodeRunMapper;
  @Mock SchedulePlanBuilder schedulePlanBuilder;
  @Mock PartitionLifecycleService partitionLifecycleService;
  @Mock TaskDispatchOutboxService taskDispatchOutboxService;
  @Mock WorkflowDagService workflowDagService;
  @Mock ResourceScheduler resourceScheduler;
  @Mock ObjectProvider<TaskExecutionService> taskExecutionServiceProvider;
  @Mock WorkflowNodePayloadBuilder payloadBuilder;
  @Mock ChildJobLaunchSupport childJobLaunchSupport;

  private DefaultWorkflowNodeDispatchService service;

  @BeforeEach
  void setUp() {
    OrchestratorJobMappers jobMappers =
        new OrchestratorJobMappers(
            jobInstanceMapper,
            jobPartitionMapper,
            jobTaskMapper,
            jobStepInstanceMapper,
            triggerRequestMapper);
    OrchestratorWorkflowMappers workflowMappers =
        new OrchestratorWorkflowMappers(
            workflowNodeMapper, workflowRunMapper, workflowNodeRunMapper);
    service =
        new DefaultWorkflowNodeDispatchService(
            jobMappers,
            workflowMappers,
            schedulePlanBuilder,
            partitionLifecycleService,
            taskDispatchOutboxService,
            workflowDagService,
            resourceScheduler,
            taskExecutionServiceProvider,
            payloadBuilder,
            childJobLaunchSupport,
            mock(CrossDayDependencyResolver.class));
  }

  @Test
  void dispatchNode_nodeAlreadyActive_usesSelectLatestForUpdate() {
    // C-3: isNodeAlreadyActivated 通过 selectLatestForUpdate 行锁防止 TOCTOU
    WorkflowNodeRunEntity existingRun = new WorkflowNodeRunEntity();
    existingRun.setId(1L);
    existingRun.setNodeCode("NODE_A");
    existingRun.setNodeStatus("RUNNING");

    when(workflowNodeRunMapper.selectLatestForUpdate(anyLong(), anyString()))
        .thenReturn(existingRun);

    JobInstanceEntity jobInstance = new JobInstanceEntity();
    jobInstance.setId(100L);
    jobInstance.setTenantId("t1");

    WorkflowRunEntity workflowRun = new WorkflowRunEntity();
    workflowRun.setId(10L);
    workflowRun.setTenantId("t1");
    workflowRun.setRelatedJobInstanceId(100L);
    workflowRun.setWorkflowDefinitionId(5L);

    WorkflowDagService.DagNodeResolution node =
        new WorkflowDagService.DagNodeResolution("NODE_A", "TASK");

    // P1-4 after-fix：isNodeAlreadyActivated (FOR UPDATE) 现在跑在 readiness 检查之前。
    // 节点已激活时直接短路返回 0，不会再触达 isNodeReadyForDispatch / selectByWorkflowDefinitionIdAndNodeCode。
    int result = service.dispatchNode(jobInstance, workflowRun, node, "{}", "trace-1");

    verify(workflowNodeRunMapper).selectLatestForUpdate(10L, "NODE_A");
    assertThat(result).isZero();
  }
}
