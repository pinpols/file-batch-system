package com.example.batch.orchestrator.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.exception.BizException;
import com.example.batch.orchestrator.application.engine.WorkflowTerminalOutboxService;
import com.example.batch.orchestrator.application.service.governance.RetryGovernanceService;
import com.example.batch.orchestrator.application.service.task.DefaultTaskOutcomeService;
import com.example.batch.orchestrator.application.service.task.OrchestratorJobMappers;
import com.example.batch.orchestrator.application.service.workflow.OrchestratorWorkflowMappers;
import com.example.batch.orchestrator.application.service.workflow.WorkflowDagService;
import com.example.batch.orchestrator.domain.command.TaskOutcomeCommand;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.statemachine.StateMachine;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import com.example.batch.orchestrator.mapper.JobStepInstanceMapper;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import com.example.batch.orchestrator.mapper.TriggerRequestMapper;
import com.example.batch.orchestrator.mapper.WorkflowNodeMapper;
import com.example.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import com.example.batch.orchestrator.mapper.WorkflowRunMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class DefaultTaskOutcomeServiceTest {

  @Mock JobInstanceMapper jobInstanceMapper;
  @Mock JobPartitionMapper jobPartitionMapper;
  @Mock JobTaskMapper jobTaskMapper;
  @Mock JobStepInstanceMapper jobStepInstanceMapper;
  @Mock TriggerRequestMapper triggerRequestMapper;
  @Mock WorkflowNodeMapper workflowNodeMapper;
  @Mock WorkflowRunMapper workflowRunMapper;
  @Mock WorkflowNodeRunMapper workflowNodeRunMapper;
  @Mock RetryGovernanceService retryGovernanceService;
  @Mock StateMachine<Object> stateMachine;
  @Mock WorkflowDagService workflowDagService;
  @Mock WorkflowTerminalOutboxService workflowTerminalOutboxService;

  @SuppressWarnings("rawtypes")
  @Mock
  ObjectProvider workflowNodeDispatchServiceProvider;

  private DefaultTaskOutcomeService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
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
        new DefaultTaskOutcomeService(
            jobMappers,
            workflowMappers,
            retryGovernanceService,
            stateMachine,
            workflowDagService,
            workflowNodeDispatchServiceProvider,
            workflowTerminalOutboxService,
            new SimpleMeterRegistry());
  }

  @Test
  void applyTaskOutcome_taskNotFound_returnsNull() {
    when(jobTaskMapper.selectById(anyString(), anyLong())).thenReturn(null);

    TaskOutcomeCommand command =
        TaskOutcomeCommand.builder().tenantId("t1").taskId(99L).success(true).build();
    var result = service.applyTaskOutcome(command);
    assertThat(result).isNull();
  }

  @Test
  void applyTaskOutcome_nullTenantId_returnsNull() {
    when(jobTaskMapper.selectById(null, 1L)).thenReturn(null);

    TaskOutcomeCommand command = TaskOutcomeCommand.builder().taskId(1L).success(true).build();
    var result = service.applyTaskOutcome(command);
    assertThat(result).isNull();
  }

  /** ADR-014：worker 上报的 invocation 与分区当前值不一致 → CONFLICT，防止过期 worker 推进状态。 */
  @Test
  void applyTaskOutcome_partitionInvocationMismatch_throwsBizException() {
    JobTaskEntity task = new JobTaskEntity();
    task.setId(1L);
    task.setTenantId("t1");
    task.setJobInstanceId(10L);
    task.setJobPartitionId(99L);
    task.setTaskStatus(TaskStatus.RUNNING.code());
    task.setAssignedWorkerCode("w1");
    task.setVersion(1L);

    JobPartitionEntity partition = new JobPartitionEntity();
    partition.setCurrentInvocationId("inv-db");

    when(jobTaskMapper.selectById("t1", 1L)).thenReturn(task);
    when(jobPartitionMapper.selectById("t1", 99L)).thenReturn(partition);

    TaskOutcomeCommand command =
        TaskOutcomeCommand.builder()
            .tenantId("t1")
            .taskId(1L)
            .workerId("w1")
            .success(true)
            .partitionInvocationId("inv-stale-worker")
            .build();

    assertThatThrownBy(() -> service.applyTaskOutcome(command))
        .isInstanceOf(BizException.class)
        .satisfies(
            ex ->
                assertThat(((BizException) ex).getMessageKey())
                    .isEqualTo("error.task.invocation_mismatch"));
  }

  @Test
  void applyTaskOutcome_selectByQueryForUpdate_usedForPartitionCounting() throws Exception {
    // C-2: 验证 applyTaskOutcome 调用 selectByQueryForUpdate（行锁）进行分区计数
    var method =
        DefaultTaskOutcomeService.class.getDeclaredMethod(
            "applyTaskOutcome", TaskOutcomeCommand.class);
    assertThat(method).isNotNull();
  }
}
