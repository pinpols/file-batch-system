package io.github.pinpols.batch.orchestrator.application.service.governance;

import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.WorkflowNodeRunStatus;
import io.github.pinpols.batch.common.enums.WorkflowNodeType;
import io.github.pinpols.batch.common.enums.WorkflowRunStatus;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 按不可变的租户、根运行和节点运行身份解析失败 JOB 节点的重放目标。 */
@Component
@RequiredArgsConstructor
public class WorkflowNodeReplayTargetResolver {

  private final WorkflowRunMapper workflowRunMapper;
  private final WorkflowNodeRunMapper workflowNodeRunMapper;
  private final JobInstanceMapper jobInstanceMapper;
  private final JobTaskMapper jobTaskMapper;

  @Transactional
  public WorkflowNodeReplayTarget resolve(String tenantId, Long workflowRunId, Long nodeRunId) {
    boolean nodeReplayIdentityIsIncomplete = EmptyChecks.isNull(tenantId)
        || EmptyChecks.isBlank(tenantId)
        || EmptyChecks.isNull(workflowRunId)
        || EmptyChecks.isNull(nodeRunId);
    if (nodeReplayIdentityIsIncomplete) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.invalid_request");
    }

    WorkflowRunEntity sourceRun =
        workflowRunMapper.selectByTenantAndIdForUpdate(tenantId, workflowRunId);
    if (EmptyChecks.isNull(sourceRun)) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.workflow.replay_source_run_not_found");
    }
    if (!WorkflowRunStatus.FAILED.code().equals(sourceRun.getRunStatus())
        || Boolean.TRUE.equals(sourceRun.getDryRun())) {
      throw invalidSource("source WorkflowRun must be a failed formal run");
    }

    WorkflowNodeRunEntity sourceNodeRun =
        workflowNodeRunMapper.selectByTenantRunAndIdForUpdate(tenantId, workflowRunId, nodeRunId);
    if (EmptyChecks.isNull(sourceNodeRun)) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.workflow.replay_source_node_not_found");
    }
    boolean sourceNodeCannotReplayFailedJob =
        !WorkflowNodeType.JOB.code().equals(sourceNodeRun.getNodeType())
            || !WorkflowNodeRunStatus.FAILED.code().equals(sourceNodeRun.getNodeStatus())
            || EmptyChecks.isNull(sourceNodeRun.getJobInstanceId());
    if (sourceNodeCannotReplayFailedJob) {
      throw invalidSource("source node must be a failed JOB node with a JobInstance");
    }

    JobInstanceEntity sourceJobInstance =
        jobInstanceMapper.selectById(tenantId, sourceNodeRun.getJobInstanceId());
    boolean sourceJobInstanceMatchesFailedNode = EmptyChecks.isNotNull(sourceJobInstance)
        && JobInstanceStatus.unsuccessfulTerminalCodes()
            .contains(sourceJobInstance.getInstanceStatus())
        && !Boolean.TRUE.equals(sourceJobInstance.getDryRun())
        && EmptyChecks.isNotNull(sourceRun.getBizDate())
        && sourceRun.getBizDate().equals(sourceJobInstance.getBizDate());
    if (!sourceJobInstanceMatchesFailedNode) {
      throw invalidSource("source JobInstance does not match the failed node execution");
    }
    if (jobTaskMapper.existsUnknownExternalOutcomeForJobInstance(
        tenantId, sourceJobInstance.getId())) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.workflow.replay_external_outcome_unknown");
    }
    return new WorkflowNodeReplayTarget(sourceRun, sourceNodeRun, sourceJobInstance);
  }

  private BizException invalidSource(String detail) {
    return BizException.of(
        ResultCode.STATE_CONFLICT, ResultCode.STATE_CONFLICT.detailKey(), detail);
  }

  public record WorkflowNodeReplayTarget(
      WorkflowRunEntity sourceRun,
      WorkflowNodeRunEntity sourceNodeRun,
      JobInstanceEntity sourceJobInstance) {}
}
