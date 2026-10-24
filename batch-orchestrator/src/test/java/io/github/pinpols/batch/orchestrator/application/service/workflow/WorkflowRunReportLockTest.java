package io.github.pinpols.batch.orchestrator.application.service.workflow;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowDefinitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class WorkflowRunReportLockTest {

  private static final String TENANT_ID = "tenant-a";
  private static final Long JOB_INSTANCE_ID = 42L;
  private static final Long WORKFLOW_RUN_ID = 84L;
  private static final Long WORKFLOW_DEFINITION_ID = 21L;
  private static final String CALENDAR_CODE = "BUSINESS";
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, 10, 22);

  private final WorkflowRunMapper workflowRunMapper = mock(WorkflowRunMapper.class);
  private final WorkflowDefinitionMapper workflowDefinitionMapper =
      mock(WorkflowDefinitionMapper.class);
  private final BatchDayInstanceMapper batchDayInstanceMapper =
      mock(BatchDayInstanceMapper.class);
  private WorkflowRunReportLock reportLock;

  @BeforeEach
  void setUp() {
    OrchestratorWorkflowMappers workflowMappers = new OrchestratorWorkflowMappers(
        mock(io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeMapper.class),
        workflowRunMapper,
        mock(io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeRunMapper.class));
    reportLock = new WorkflowRunReportLock(
        workflowMappers, workflowDefinitionMapper, batchDayInstanceMapper);
  }

  @Test
  void acquiresBatchDayFenceBeforeWorkflowRunRowLock() {
    WorkflowRunEntity workflowRun = workflowRun();
    WorkflowRunEntity lockedRun = workflowRun();
    WorkflowDefinitionEntity definition = workflowDefinition();
    when(workflowRunMapper.selectByNodeJobInstanceId(TENANT_ID, JOB_INSTANCE_ID))
        .thenReturn(workflowRun);
    when(workflowDefinitionMapper.selectById(WORKFLOW_DEFINITION_ID)).thenReturn(definition);
    when(workflowRunMapper.selectByTenantAndIdForUpdate(TENANT_ID, WORKFLOW_RUN_ID))
        .thenReturn(lockedRun);

    reportLock.acquireForJobInstance(TENANT_ID, JOB_INSTANCE_ID);

    InOrder lockOrder = inOrder(batchDayInstanceMapper, workflowRunMapper);
    lockOrder.verify(batchDayInstanceMapper)
        .acquireSettlementFence(TENANT_ID, CALENDAR_CODE, BIZ_DATE);
    lockOrder.verify(workflowRunMapper)
        .selectByTenantAndIdForUpdate(TENANT_ID, WORKFLOW_RUN_ID);
  }

  @Test
  void doesNotAcquireWorkflowLocksForNonWorkflowJobInstances() {
    when(workflowRunMapper.selectByNodeJobInstanceId(TENANT_ID, JOB_INSTANCE_ID))
        .thenReturn(null);

    reportLock.acquireForJobInstance(TENANT_ID, JOB_INSTANCE_ID);

    verify(batchDayInstanceMapper, never()).acquireSettlementFence(
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.any(LocalDate.class));
    verify(workflowRunMapper, never()).selectByTenantAndIdForUpdate(
        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
  }

  private WorkflowRunEntity workflowRun() {
    WorkflowRunEntity workflowRun = new WorkflowRunEntity();
    workflowRun.setId(WORKFLOW_RUN_ID);
    workflowRun.setTenantId(TENANT_ID);
    workflowRun.setWorkflowDefinitionId(WORKFLOW_DEFINITION_ID);
    workflowRun.setBizDate(BIZ_DATE);
    return workflowRun;
  }

  private WorkflowDefinitionEntity workflowDefinition() {
    return new WorkflowDefinitionEntity(
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
