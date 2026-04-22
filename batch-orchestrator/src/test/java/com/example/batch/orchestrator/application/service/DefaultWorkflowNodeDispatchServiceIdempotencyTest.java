package com.example.batch.orchestrator.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.persistence.entity.WorkflowRunEntity;
import com.example.batch.orchestrator.application.engine.TaskDispatchOutboxService;
import com.example.batch.orchestrator.application.plan.SchedulePlanBuilder;
import com.example.batch.orchestrator.application.scheduler.ResourceScheduler;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import com.example.batch.orchestrator.mapper.JobStepInstanceMapper;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import com.example.batch.orchestrator.mapper.TriggerRequestMapper;
import com.example.batch.orchestrator.mapper.WorkflowNodeMapper;
import com.example.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import com.example.batch.orchestrator.mapper.WorkflowRunMapper;
import com.example.batch.orchestrator.service.LaunchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

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
  @Mock ObjectProvider<LaunchService> launchServiceProvider;
  @Mock NamedParameterJdbcTemplate namedParameterJdbcTemplate;

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
            launchServiceProvider,
            namedParameterJdbcTemplate);
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

    // 前置条件：DAG 检查通过，节点定义存在
    when(workflowDagService.isNodeReadyForDispatch(anyLong(), anyLong(), anyString(), anyString()))
        .thenReturn(true);
    WorkflowNodeEntity workflowNode = new WorkflowNodeEntity();
    workflowNode.setNodeCode("NODE_A");
    workflowNode.setNodeType("TASK");
    when(workflowNodeMapper.selectByWorkflowDefinitionIdAndNodeCode(anyLong(), anyString()))
        .thenReturn(workflowNode);

    // 节点已激活时应短路返回 0，不创建新分区
    int result = service.dispatchNode(jobInstance, workflowRun, node, "{}", "trace-1");

    verify(workflowNodeRunMapper).selectLatestForUpdate(10L, "NODE_A");
    assertThat(result).isZero();
  }
}
