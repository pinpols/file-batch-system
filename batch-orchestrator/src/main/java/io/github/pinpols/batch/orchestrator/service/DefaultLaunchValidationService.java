package io.github.pinpols.batch.orchestrator.service;

import io.github.pinpols.batch.orchestrator.domain.param.TriggerRequestIsValidParentWorkflowNodeLaunchParam;


import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.ReplayExecutionGuard;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.TriggerRequestStatus;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.logging.LogSanitizer;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunExecutionSnapshot;
import io.github.pinpols.batch.orchestrator.domain.context.TriggerLaunchPersistenceContext;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Launch 前置校验与配置加载：把 trigger_request / job_definition / workflow_definition / 现有 instance 一次性读齐供
 * {@link DefaultLaunchService} 使用；校验失败会把 {@code trigger_request} 打成 REJECTED 再抛异常， 避免请求挂在 ACCEPTED
 * 永远没落地。
 *
 * <p>根运行只能由 WorkflowRunLaunchService 接纳。Job 节点子请求从冻结的父 Workflow 快照加载 Job；旧 WORKFLOW Job
 * wrapper 会在这里被拒绝，不再按同编码回退加载 Workflow。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultLaunchValidationService implements LaunchValidationService {

  private final TriggerRequestMapper triggerRequestMapper;
  private final OrchestratorConfigCacheService configCacheService;
  private final WorkflowRunMapper workflowRunMapper;

  /**
   * 注意副作用：job/workflow 定义缺失时，本方法会把 trigger_request 状态更新为 REJECTED 后再抛 {@code
   * NOT_FOUND}——保证请求有终态而非停留在 ACCEPTED。调用方不需要也不应重复打 REJECTED。
   */
  @Override
  public LaunchLoadResult load(LaunchRequest request) {
    validate(request);

    TriggerLaunchPersistenceContext persistenceContext =
        triggerRequestMapper.selectLaunchPersistenceContext(
            request.tenantId(), request.requestId());
    if (persistenceContext == null || persistenceContext.getTriggerRequest() == null) {
      throw BizException.of(
          ResultCode.NOT_FOUND, "error.trigger.request_not_found", request.requestId());
    }
    TriggerRequestEntity triggerRequest = persistenceContext.getTriggerRequest();

    boolean parentNodeLaunch = hasParentWorkflowNodeContext(triggerRequest);
    ParentWorkflowJobSnapshot parentJobSnapshot =
        parentNodeLaunch ? loadJobDefinitionFromWorkflowSnapshot(request, triggerRequest) : null;
    JobDefinitionEntity jobDefinition =
        EmptyChecks.isNull(parentJobSnapshot) ? null : parentJobSnapshot.jobDefinition();
    String workflowCalendarCode =
        EmptyChecks.isNull(parentJobSnapshot) ? null : parentJobSnapshot.workflowCalendarCode();
    if (EmptyChecks.isNull(jobDefinition)) {
      jobDefinition =
          configCacheService.findEnabledJobDefinition(request.tenantId(), request.jobCode());
    }
    if (jobDefinition == null) {
      int updatedRows = triggerRequestMapper.updateAcceptance(
          request.tenantId(), request.requestId(), TriggerRequestStatus.REJECTED.code(), null);
      warnIfNotUpdated(updatedRows, request);
      throw BizException.of(ResultCode.NOT_FOUND, "error.job.definition_not_found");
    }

    if ("WORKFLOW".equals(jobDefinition.jobType())) {
      int updatedRows = triggerRequestMapper.updateAcceptance(
          request.tenantId(), request.requestId(), TriggerRequestStatus.REJECTED.code(), null);
      warnIfNotUpdated(updatedRows, request);
      throw BizException.of(ResultCode.VALIDATION_ERROR, "error.workflow.wrapper_job_retired");
    }

    if (!parentNodeLaunch) {
      int updatedRows = triggerRequestMapper.updateAcceptance(
          request.tenantId(), request.requestId(), TriggerRequestStatus.REJECTED.code(), null);
      warnIfNotUpdated(updatedRows, request);
      throw BizException.of(ResultCode.VALIDATION_ERROR, "error.workflow.root_job_launch_retired");
    }

    if (parentNodeLaunch
        && !isValidParentWorkflowNodeLaunch(
            request,
            triggerRequest,
            persistenceContext,
            EmptyChecks.isNotNull(parentJobSnapshot))) {
      int updatedRows = triggerRequestMapper.updateAcceptance(
          request.tenantId(), request.requestId(), TriggerRequestStatus.REJECTED.code(), null);
      warnIfNotUpdated(updatedRows, request);
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.workflow.parent_node_launch_invalid");
    }

    JobInstanceEntity existingInstance = persistenceContext.getExistingInstance();

    return new LaunchLoadResult(
        triggerRequest,
        jobDefinition,
        null,
        workflowCalendarCode,
        existingInstance,
        EmptyChecks.isNull(parentJobSnapshot)
            ? null
            : parentJobSnapshot.workflowReplayExecutionBinding());
  }

  private ParentWorkflowJobSnapshot loadJobDefinitionFromWorkflowSnapshot(
      LaunchRequest request, TriggerRequestEntity triggerRequest) {
    if (EmptyChecks.isNull(triggerRequest.getParentWorkflowRunId())
        || EmptyChecks.isNull(triggerRequest.getParentNodeCode())) {
      rejectInvalidExecutionSnapshot(request, triggerRequest);
    }
    WorkflowRunEntity run =
        workflowRunMapper.selectById(request.tenantId(), triggerRequest.getParentWorkflowRunId());
    WorkflowRunExecutionSnapshot snapshot = WorkflowRunExecutionSnapshot.from(run);
    if (EmptyChecks.isNull(snapshot)) {
      rejectInvalidExecutionSnapshot(request, triggerRequest);
    }
    Integer schemaVersion = snapshot.schemaVersion();
    if (EmptyChecks.isNull(schemaVersion) || schemaVersion < 2) {
      rejectInvalidExecutionSnapshot(request, triggerRequest);
    }
    boolean snapshotTenantMatches = request.tenantId().equals(run.getTenantId());
    if (!snapshotTenantMatches) {
      rejectInvalidExecutionSnapshot(request, triggerRequest);
    }
    boolean snapshotWorkflowMatches =
        Objects.equals(run.getWorkflowDefinitionId(), snapshot.workflowDefinitionId());
    if (!snapshotWorkflowMatches) {
      rejectInvalidExecutionSnapshot(request, triggerRequest);
    }
    WorkflowNodeEntity node = snapshot.node(triggerRequest.getParentNodeCode());
    JobDefinitionEntity definition = snapshot.jobDefinition(triggerRequest.getParentNodeCode());
    if (EmptyChecks.isNull(node)) {
      rejectInvalidExecutionSnapshot(request, triggerRequest);
    }
    boolean nodeReferencesRequestedJob = Objects.equals(node.getRelatedJobCode(), request.jobCode());
    if (!nodeReferencesRequestedJob) {
      rejectInvalidExecutionSnapshot(request, triggerRequest);
    }
    if (EmptyChecks.isNull(definition)) {
      rejectInvalidExecutionSnapshot(request, triggerRequest);
    }
    boolean definitionTenantMatches = request.tenantId().equals(definition.tenantId());
    if (!definitionTenantMatches) {
      rejectInvalidExecutionSnapshot(request, triggerRequest);
    }
    boolean definitionJobMatches = request.jobCode().equals(definition.jobCode());
    if (!definitionJobMatches) {
      rejectInvalidExecutionSnapshot(request, triggerRequest);
    }
    WorkflowReplayExecutionBinding replayBinding = null;
    if (EmptyChecks.isNotNull(snapshot.replayOperationId())) {
      Long replayOperationId = snapshot.replayOperationId();
      Long workflowRunId = run.getId();
      String nodeCode = triggerRequest.getParentNodeCode();
      ReplayExecutionGuard executionGuard =
          snapshot.replayExecutionGuard(triggerRequest.getParentNodeCode());
      Map<String, String> protectedFileTemplateSnapshots =
          snapshot.frozenFileTemplateSnapshots(triggerRequest.getParentNodeCode());
      Map<String, String> protectedDispatchChannelSnapshots =
          snapshot.frozenDispatchChannelSnapshots(triggerRequest.getParentNodeCode());
      Map<String, Object> dispatchSecretBindings =
          snapshot.dispatchSecretBindings(triggerRequest.getParentNodeCode());
      replayBinding = new WorkflowReplayExecutionBinding(
          replayOperationId,
          workflowRunId,
          nodeCode,
          executionGuard,
          protectedFileTemplateSnapshots,
          protectedDispatchChannelSnapshots,
          dispatchSecretBindings);
    }
    return new ParentWorkflowJobSnapshot(
        definition, snapshot.workflowCalendarCode(), replayBinding);
  }

  private record ParentWorkflowJobSnapshot(
      JobDefinitionEntity jobDefinition,
      String workflowCalendarCode,
      WorkflowReplayExecutionBinding workflowReplayExecutionBinding) {}

  private void rejectInvalidExecutionSnapshot(
      LaunchRequest request, TriggerRequestEntity triggerRequest) {
    int updatedRows = triggerRequestMapper.updateAcceptance(
        request.tenantId(), request.requestId(), TriggerRequestStatus.REJECTED.code(), null);
    warnIfNotUpdated(updatedRows, request);
    throw BizException.of(ResultCode.STATE_CONFLICT, "error.workflow.execution_snapshot_invalid");
  }

  private boolean hasParentWorkflowNodeContext(TriggerRequestEntity triggerRequest) {
    return EmptyChecks.isNotNull(triggerRequest.getParentWorkflowRunId())
        || EmptyChecks.isNotNull(triggerRequest.getParentNodeCode());
  }

  private boolean isValidParentWorkflowNodeLaunch(
      LaunchRequest request,
      TriggerRequestEntity triggerRequest,
      TriggerLaunchPersistenceContext persistenceContext,
      boolean frozenWorkflowSnapshot) {
    boolean parentWorkflowNodeIdentityIsIncomplete =
        EmptyChecks.isNull(triggerRequest.getParentWorkflowRunId())
        || EmptyChecks.isNull(triggerRequest.getParentNodeCode())
        || EmptyChecks.isBlank(triggerRequest.getParentNodeCode());
    if (parentWorkflowNodeIdentityIsIncomplete) {
      return false;
    }
    JobInstanceEntity existingInstance = persistenceContext.getExistingInstance();
    TriggerRequestIsValidParentWorkflowNodeLaunchParam triggerRequestIsValidParentWorkflowNodeLaunchParam = TriggerRequestIsValidParentWorkflowNodeLaunchParam.builder()
        .tenantId(request.tenantId())
        .parentWorkflowRunId(triggerRequest.getParentWorkflowRunId())
        .parentNodeCode(triggerRequest.getParentNodeCode())
        .existingChildJobInstanceId(EmptyChecks.isNull(existingInstance) ? null : existingInstance.getId())
        .frozenWorkflowSnapshot(frozenWorkflowSnapshot)
        .build();
    return triggerRequestMapper.isValidParentWorkflowNodeLaunch(
        triggerRequestIsValidParentWorkflowNodeLaunchParam);
  }

  /** updateAcceptance 0 行受影响时,行已是终态(LAUNCHED/REJECTED/DUPLICATE),仅告警不抛异常。 */
  private void warnIfNotUpdated(int updatedRows, LaunchRequest request) {
    if (updatedRows == 0) {
      log.warn(
          "updateAcceptance(REJECTED) affected 0 rows; the row is already terminal: tenantId={} requestId={}",
          LogSanitizer.value(request.tenantId()),
          LogSanitizer.value(request.requestId()));
    }
  }

  private void validate(LaunchRequest request) {
    Guard.require(request != null, "launch request is required");
    if (request.tenantId() == null || request.tenantId().isBlank()) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.tenant_id_required");
    }
    if (request.jobCode() == null || request.jobCode().isBlank()) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.job.code_required");
    }
    if (request.bizDate() == null) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.biz_date_required");
    }
    if (request.triggerType() == null) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.trigger.type_required");
    }
  }
}
