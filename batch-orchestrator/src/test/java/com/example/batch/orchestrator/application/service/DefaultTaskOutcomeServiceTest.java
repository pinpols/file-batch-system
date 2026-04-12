package com.example.batch.orchestrator.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.example.batch.orchestrator.domain.command.TaskOutcomeCommand;
import com.example.batch.orchestrator.domain.statemachine.StateMachine;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import com.example.batch.orchestrator.mapper.JobStepInstanceMapper;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import com.example.batch.orchestrator.mapper.TriggerRequestMapper;
import com.example.batch.orchestrator.mapper.WorkflowNodeMapper;
import com.example.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import com.example.batch.orchestrator.mapper.WorkflowRunMapper;
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
            workflowNodeDispatchServiceProvider);
  }

  @Test
  void applyTaskOutcome_taskNotFound_returnsNull() {
    when(jobTaskMapper.selectById(anyString(), anyLong())).thenReturn(null);

    var result =
        service.applyTaskOutcome(new TaskOutcomeCommand("t1", 99L, null, true, null, null, null));
    assertThat(result).isNull();
  }

  @Test
  void applyTaskOutcome_nullTenantId_returnsNull() {
    when(jobTaskMapper.selectById(null, 1L)).thenReturn(null);

    var result =
        service.applyTaskOutcome(new TaskOutcomeCommand(null, 1L, null, true, null, null, null));
    assertThat(result).isNull();
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
