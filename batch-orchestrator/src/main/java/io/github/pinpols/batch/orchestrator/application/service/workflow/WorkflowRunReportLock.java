package io.github.pinpols.batch.orchestrator.application.service.workflow;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowDefinitionMapper;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/** Worker 回报修改节点状态前，先获取业务日与 WorkflowRun 锁。 */
@Component
public final class WorkflowRunReportLock {

  private final OrchestratorWorkflowMappers workflowMappers;
  private final WorkflowDefinitionMapper workflowDefinitionMapper;
  private final BatchDayInstanceMapper batchDayInstanceMapper;

  public WorkflowRunReportLock(
      OrchestratorWorkflowMappers workflowMappers,
      WorkflowDefinitionMapper workflowDefinitionMapper,
      BatchDayInstanceMapper batchDayInstanceMapper) {
    this.workflowMappers = workflowMappers;
    this.workflowDefinitionMapper = workflowDefinitionMapper;
    this.batchDayInstanceMapper = batchDayInstanceMapper;
  }

  public void acquireForJobInstance(String tenantId, Long jobInstanceId) {
    WorkflowRunEntity workflowRun =
        workflowMappers.workflowRunMapper.selectByNodeJobInstanceId(tenantId, jobInstanceId);
    if (EmptyChecks.isNull(workflowRun)) {
      return;
    }

    String calendarCode = resolveCalendarCode(workflowRun);
    LocalDate bizDate = workflowRun.getBizDate();
    if (EmptyChecks.isBlank(calendarCode) || EmptyChecks.isNull(bizDate)) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.workflow.execution_snapshot_invalid");
    }

    batchDayInstanceMapper.acquireSettlementFence(tenantId, calendarCode, bizDate);
    Long workflowRunId = workflowRun.getId();
    WorkflowRunEntity lockedRun =
        workflowMappers.workflowRunMapper.selectByTenantAndIdForUpdate(tenantId, workflowRunId);
    if (EmptyChecks.isNull(lockedRun)) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.workflow.execution_snapshot_invalid");
    }
  }

  private String resolveCalendarCode(WorkflowRunEntity workflowRun) {
    WorkflowRunExecutionSnapshot executionSnapshot = WorkflowRunExecutionSnapshot.from(workflowRun);
    if (EmptyChecks.isNotNull(executionSnapshot)) {
      String calendarCode = executionSnapshot.workflowCalendarCode();
      if (EmptyChecks.isNotBlank(calendarCode)) {
        return calendarCode;
      }
    }
    Long workflowDefinitionId = workflowRun.getWorkflowDefinitionId();
    WorkflowDefinitionEntity workflowDefinition =
        workflowDefinitionMapper.selectById(workflowDefinitionId);
    if (EmptyChecks.isNull(workflowDefinition)) {
      return null;
    }
    return workflowDefinition.calendarCode();
  }
}
