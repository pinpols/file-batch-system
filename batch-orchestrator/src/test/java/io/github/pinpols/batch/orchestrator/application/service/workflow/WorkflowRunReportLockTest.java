package io.github.pinpols.batch.orchestrator.application.service.workflow;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.orchestrator.domain.view.WorkflowDefinitionView;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowDefinitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("运行回报锁的业务日栅栏顺序")
@ExtendWith(MockitoExtension.class)
class WorkflowRunReportLockTest {

  private static final String TENANT_ID = "tenant-a";
  private static final Long JOB_INSTANCE_ID = 42L;
  private static final Long WORKFLOW_RUN_ID = 84L;
  private static final Long WORKFLOW_DEFINITION_ID = 21L;
  private static final String CALENDAR_CODE = "BUSINESS";
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, 10, 22);

  @Mock
  private WorkflowRunMapper workflowRunMapper;

  @Mock
  private WorkflowDefinitionMapper workflowDefinitionMapper;

  @Mock
  private BatchDayInstanceMapper batchDayInstanceMapper;

  private WorkflowRunReportLock reportLock;

  @BeforeEach
  void setUp() {
    OrchestratorWorkflowMappers workflowMappers = new OrchestratorWorkflowMappers(
        mock(WorkflowNodeMapper.class), workflowRunMapper, mock(WorkflowNodeRunMapper.class));
    reportLock = new WorkflowRunReportLock(
        workflowMappers, workflowDefinitionMapper, batchDayInstanceMapper);
  }

  @DisplayName("WorkflowRun 行锁之前先取得业务日栅栏")
  @Test
  void shouldAcquireBatchDayFence_whenLockingWorkflowRun() {
    WorkflowRunEntity workflowRun = workflowRun();
    WorkflowRunEntity lockedRun = workflowRun();
    WorkflowDefinitionView definition = workflowDefinition();
    when(workflowRunMapper.selectByNodeJobInstanceId(TENANT_ID, JOB_INSTANCE_ID))
        .thenReturn(workflowRun);
    when(workflowDefinitionMapper.selectById(WORKFLOW_DEFINITION_ID)).thenReturn(definition);
    when(workflowRunMapper.selectByTenantAndIdForUpdate(TENANT_ID, WORKFLOW_RUN_ID))
        .thenReturn(lockedRun);

    reportLock.acquireForJobInstance(TENANT_ID, JOB_INSTANCE_ID);

    InOrder lockOrder = inOrder(batchDayInstanceMapper, workflowRunMapper);
    lockOrder
        .verify(batchDayInstanceMapper)
        .acquireSettlementFence(TENANT_ID, CALENDAR_CODE, BIZ_DATE);
    lockOrder.verify(workflowRunMapper).selectByTenantAndIdForUpdate(TENANT_ID, WORKFLOW_RUN_ID);
  }

  @DisplayName("非 Workflow 作业实例不取得 Workflow 锁")
  @Test
  void shouldAvoidWorkflowLocks_whenJobHasNoWorkflow() {
    when(workflowRunMapper.selectByNodeJobInstanceId(TENANT_ID, JOB_INSTANCE_ID))
        .thenReturn(null);

    reportLock.acquireForJobInstance(TENANT_ID, JOB_INSTANCE_ID);

    verify(batchDayInstanceMapper, never())
        .acquireSettlementFence(
            ArgumentMatchers.anyString(),
            ArgumentMatchers.anyString(),
            ArgumentMatchers.any(LocalDate.class));
    verify(workflowRunMapper, never())
        .selectByTenantAndIdForUpdate(ArgumentMatchers.anyString(), ArgumentMatchers.any());
  }

  private WorkflowRunEntity workflowRun() {
    WorkflowRunEntity workflowRun = new WorkflowRunEntity();
    workflowRun.setId(WORKFLOW_RUN_ID);
    workflowRun.setTenantId(TENANT_ID);
    workflowRun.setWorkflowDefinitionId(WORKFLOW_DEFINITION_ID);
    workflowRun.setBizDate(BIZ_DATE);
    return workflowRun;
  }

  private WorkflowDefinitionView workflowDefinition() {
    return new WorkflowDefinitionView(
        WORKFLOW_DEFINITION_ID,
        TENANT_ID,
        "workflow-a",
        "Workflow A",
        "STANDARD",
        1,
        true,
        null,
        CALENDAR_CODE);
  }
}
