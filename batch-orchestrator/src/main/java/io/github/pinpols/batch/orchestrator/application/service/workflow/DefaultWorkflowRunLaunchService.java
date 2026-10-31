package io.github.pinpols.batch.orchestrator.application.service.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.enums.DictEnum;
import io.github.pinpols.batch.common.enums.JobType;
import io.github.pinpols.batch.common.enums.ReplayOperationStatus;
import io.github.pinpols.batch.common.enums.ReplayOperationType;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.SystemPipelineCatalog;
import io.github.pinpols.batch.common.enums.TriggerRequestStatus;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.enums.WorkflowNodeCode;
import io.github.pinpols.batch.common.enums.WorkflowNodeRunStatus;
import io.github.pinpols.batch.common.enums.WorkflowNodeType;
import io.github.pinpols.batch.common.enums.WorkflowRunStatus;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.jdbc.JdbcExceptionSupport;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.IdGenerator;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.application.plan.BundlePlanParams;
import io.github.pinpols.batch.orchestrator.application.service.batchday.ExternalEventExpectationService;
import io.github.pinpols.batch.orchestrator.application.service.batchday.ExternalEventExpectationService.SatisfactionRequest;
import io.github.pinpols.batch.orchestrator.application.service.governance.RelatedFileReferenceValidator;
import io.github.pinpols.batch.orchestrator.application.service.replay.WorkflowRunReplaySnapshotResolver;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowDagService.DagNodeResolution;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.ReplayOperationEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowEdgeEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.domain.param.ReplayOperationWorkflowOutcomeParam;
import io.github.pinpols.batch.orchestrator.domain.param.WorkflowRunMarkRunningParam;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.ReplayOperationMapper;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowDefinitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowEdgeMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import io.github.pinpols.batch.orchestrator.service.LaunchParamResolver;
import io.github.pinpols.batch.orchestrator.service.LaunchRequestIdentityGuard;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 为显式 Workflow Trigger 目标创建根 Workflow Run，并派发入口节点。 */
@Service
@RequiredArgsConstructor
public class DefaultWorkflowRunLaunchService implements WorkflowRunLaunchService {

  /** 解析 workflow-run replay 的实际启动参数与快照，避免 launch 主流程过长。 */
  private ReplayResolution resolveReplayResolution(
      LaunchRequest request,
      WorkflowRunEntity existing,
      WorkflowLaunchWaitSnapshot capacitySnapshot) {
    ReplayOperationEntity replayOperation = findReplayOperation(request);
    WorkflowRunReplaySnapshotResolver.Snapshot replaySnapshot = null;
    LaunchRequest effectiveRequest = request;
    if (EmptyChecks.isNotNull(replayOperation)
        && ReplayOperationType.WORKFLOW_RUN.code().equals(replayOperation.operationType())) {
      boolean replayOperationCannotStartNewRun = EmptyChecks.isNull(existing)
          && (!ReplayOperationStatus.RUNNING.code().equals(replayOperation.status())
              || !request.requestId().equals(replayOperation.targetRequestId()));
      if (replayOperationCannotStartNewRun) {
        rejectIfUnprepared(request);
        throw BizException.of(
            ResultCode.STATE_CONFLICT, "error.workflow.workflow_run_replay_approval_state_invalid");
      }
      if (EmptyChecks.isNull(existing) && EmptyChecks.isNull(capacitySnapshot)) {
        try {
          replaySnapshot = replaySnapshotResolver.resolve(replayOperation, request.requestId());
        } catch (BizException invalidSnapshot) {
          rejectIfUnprepared(request);
          throw invalidSnapshot;
        }
      }
      boolean requestDiffersFromReplaySnapshot = EmptyChecks.isNotNull(replaySnapshot)
          && (!Objects.equals(request.workflowDefinitionId(), replaySnapshot.workflowDefinitionId())
              || !Objects.equals(request.bizDate(), replaySnapshot.bizDate())
              || Boolean.TRUE.equals(request.dryRun())
                  != Boolean.TRUE.equals(replaySnapshot.dryRun())
              || !Objects.equals(request.dataIntervalStart(), replaySnapshot.dataIntervalStart())
              || !Objects.equals(request.dataIntervalEnd(), replaySnapshot.dataIntervalEnd()));
      if (requestDiffersFromReplaySnapshot) {
        rejectIfUnprepared(request);
        throw BizException.of(ResultCode.STATE_CONFLICT, "error.workflow.trigger_target_mismatch");
      }
      if (EmptyChecks.isNotNull(replaySnapshot)) {
        effectiveRequest = request.toBuilder()
            .workflowDefinitionId(replaySnapshot.workflowDefinitionId())
            .bizDate(replaySnapshot.bizDate())
            .dataIntervalStart(replaySnapshot.dataIntervalStart())
            .dataIntervalEnd(replaySnapshot.dataIntervalEnd())
            .dryRun(replaySnapshot.dryRun())
            .params(replaySnapshot.launchParams())
            .build();
      }
    }
    return new ReplayResolution(replayOperation, replaySnapshot, effectiveRequest);
  }

  private record ReplayResolution(
      ReplayOperationEntity operation,
      WorkflowRunReplaySnapshotResolver.Snapshot snapshot,
      LaunchRequest effectiveRequest) {}

  private final TriggerRequestMapper triggerRequestMapper;
  private final WorkflowDefinitionMapper workflowDefinitionMapper;
  private final WorkflowRunMapper workflowRunMapper;
  private final ReplayOperationMapper replayOperationMapper;
  private final WorkflowRunReplaySnapshotResolver replaySnapshotResolver;
  private final JobInstanceMapper jobInstanceMapper;
  private final LaunchParamResolver launchParamResolver;
  private final WorkflowNodeRunMapper workflowNodeRunMapper;
  private final WorkflowNodeMapper workflowNodeMapper;
  private final WorkflowEdgeMapper workflowEdgeMapper;
  private final WorkflowGraphValidator workflowGraphValidator;
  private final WorkflowDagService workflowDagService;
  private final WorkflowNodeDispatchService workflowNodeDispatchService;
  private final OrchestratorConfigCacheService configCacheService;
  private final RerunJobDefinitionResolver rerunJobDefinitionResolver;
  private final PlatformTransactionManager transactionManager;
  private final WorkflowLaunchWaitService waitingLaunchService;
  private final ExternalEventExpectationService externalEventExpectationService;
  private final RelatedFileReferenceValidator relatedFileReferenceValidator;
  private final SecretPayloadProtector secretPayloadProtector;
  private final WorkflowNodeTerminalFinalizer terminalFinalizer;

  @Override
  public LaunchResponse launch(LaunchRequest request) {
    validateRequest(request);
    TriggerRequestEntity triggerRequest =
        triggerRequestMapper.selectByTenantAndRequestId(request.tenantId(), request.requestId());
    if (EmptyChecks.isNull(triggerRequest)
        || !LaunchRequestIdentityGuard.matches(request, triggerRequest)) {
      // 不匹配的载荷不拥有原请求，不能改变其状态或关联的重放操作。
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.workflow.trigger_target_mismatch");
    }
    WorkflowRunEntity existing = workflowRunMapper.selectByTenantAndTriggerRequestId(
        request.tenantId(), triggerRequest.getId());
    WorkflowLaunchWaitSnapshot capacitySnapshot =
        EmptyChecks.isNull(existing) ? waitingLaunchService.findSnapshot(request) : null;
    ReplayResolution replayResolution =
        resolveReplayResolution(request, existing, capacitySnapshot);
    ReplayOperationEntity replayOperation = replayResolution.operation();
    WorkflowRunReplaySnapshotResolver.Snapshot replaySnapshot = replayResolution.snapshot();
    LaunchRequest effectiveRequest = replayResolution.effectiveRequest();
    if (EmptyChecks.isNotNull(capacitySnapshot)) {
      requireCapacityReplayAuthorization(request, capacitySnapshot, replayOperation);
      effectiveRequest = capacitySnapshot.effectiveRequest();
    }
    WorkflowDefinitionEntity definition = null;
    boolean approvedReplay = EmptyChecks.isNotNull(replayOperation)
        && ReplayOperationType.WORKFLOW_RUN.code().equals(replayOperation.operationType());
    if (EmptyChecks.isNull(existing) && !approvedReplay) {
      definition = loadAdmissibleDefinition(request);
      if (EmptyChecks.isNull(definition)) {
        rejectIfUnprepared(request);
        throw BizException.of(ResultCode.NOT_FOUND, "error.workflow.definition_not_found");
      }
    }
    if (EmptyChecks.isNull(existing)
        && EmptyChecks.isNull(replaySnapshot)
        && EmptyChecks.isNull(capacitySnapshot)) {
      validateParentInstanceReference(request);
    }
    boolean newRunScheduleVersionDoesNotMatch = EmptyChecks.isNull(existing)
        && EmptyChecks.isNull(replaySnapshot)
        && EmptyChecks.isNull(capacitySnapshot)
        && !scheduledWorkflowVersionMatches(request, definition);
    if (newRunScheduleVersionDoesNotMatch) {
      rejectIfUnprepared(request);
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.workflow.scheduled_version_stale");
    }
    String requestedTraceId = request.traceId();
    String traceId =
        EmptyChecks.isBlank(requestedTraceId) ? IdGenerator.newTraceId() : requestedTraceId;
    if (EmptyChecks.isNotNull(capacitySnapshot)) {
      traceId = capacitySnapshot.traceId();
    }
    RunPreparation preparation = new RunPreparation(
        effectiveRequest,
        request,
        triggerRequest,
        definition,
        traceId,
        replaySnapshot,
        capacitySnapshot);
    WorkflowRunEntity run = prepareRunWithSerializationRetry(preparation);
    if (EmptyChecks.isNull(run)) {
      return new LaunchResponse(request.requestId(), traceId);
    }
    String eventCalendarCode = null;
    boolean eventLaunchHasBusinessDate =
        TriggerType.EVENT.code().equals(triggerRequest.getTriggerType())
            && EmptyChecks.isNotNull(triggerRequest.getExternalEventCode())
            && EmptyChecks.isNotNull(request.bizDate());
    if (eventLaunchHasBusinessDate) {
      eventCalendarCode = resolveWorkflowCalendarCode(run, definition);
    }
    dispatchInitialNodes(effectiveRequest, run, traceId);
    triggerRequestMapper.updateAcceptance(
        request.tenantId(), request.requestId(), TriggerRequestStatus.LAUNCHED.code(), null);
    if (EmptyChecks.isNotBlank(eventCalendarCode)) {
      SatisfactionRequest satisfactionRequest = new SatisfactionRequest(
          request.tenantId(),
          eventCalendarCode,
          request.workflowDefinitionId(),
          triggerRequest.getExternalEventCode(),
          request.bizDate(),
          request.requestId());
      externalEventExpectationService.markSatisfied(satisfactionRequest);
    }
    waitingLaunchService.markResumed(request);
    String workflowRunId = run.getId().toString();
    return new LaunchResponse(workflowRunId, traceId);
  }

  /** 容量等待保存执行输入，Replay 操作授权仍以本次接纳时的状态为准。 */
  private boolean requireCapacityReplayAuthorization(
      LaunchRequest request, WorkflowLaunchWaitSnapshot snapshot, ReplayOperationEntity operation) {
    Long expectedOperationId = snapshot.replayOperationId();
    if (EmptyChecks.isNull(expectedOperationId)) {
      return false;
    }
    if (EmptyChecks.isNull(operation)
        || !Objects.equals(expectedOperationId, operation.id())
        || !ReplayOperationType.WORKFLOW_RUN.code().equals(operation.operationType())
        || !ReplayOperationStatus.RUNNING.code().equals(operation.status())
        || !Objects.equals(request.requestId(), operation.targetRequestId())) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.workflow.workflow_run_replay_approval_state_invalid");
    }
    return true;
  }

  private String resolveWorkflowCalendarCode(
      WorkflowRunEntity run, WorkflowDefinitionEntity definition) {
    WorkflowRunExecutionSnapshot snapshot = WorkflowRunExecutionSnapshot.from(run);
    if (EmptyChecks.isNotNull(snapshot)) {
      String calendarCode = snapshot.workflowCalendarCode();
      if (EmptyChecks.isBlank(calendarCode)) {
        throw BizException.of(
            ResultCode.STATE_CONFLICT, "error.workflow.execution_snapshot_invalid");
      }
      return calendarCode;
    }
    if (EmptyChecks.isNotNull(definition)) {
      String calendarCode = definition.calendarCode();
      if (EmptyChecks.isNotBlank(calendarCode)) {
        return calendarCode;
      }
    }
    throw BizException.of(ResultCode.STATE_CONFLICT, "error.workflow.execution_snapshot_invalid");
  }

  private void validateParentInstanceReference(LaunchRequest request) {
    Long parentInstanceId = launchParamResolver.resolveParentInstanceId(
        EmptyChecks.isNull(request.params()) ? Map.of() : request.params());
    if (EmptyChecks.isNull(parentInstanceId)) {
      return;
    }
    JobInstanceEntity parentInstance =
        jobInstanceMapper.selectById(request.tenantId(), parentInstanceId);
    if (EmptyChecks.isNotNull(parentInstance)
        && request.tenantId().equals(parentInstance.getTenantId())) {
      return;
    }
    rejectIfUnprepared(request);
    throw BizException.of(ResultCode.NOT_FOUND, "error.job.instance_not_found");
  }

  /**
   * 持久化根运行前，先验证所选 Job 快照中的具体 bundle 文件 ID。
   * 分区创建也会验证文件归属，但此时根运行已经提交。
   */
  private void validateBundleFileReferences(
      LaunchRequest request, WorkflowNodeEntity node, JobDefinitionEntity job) {
    JobType jobType = DictEnum.fromCode(JobType.class, job.jobType());
    boolean nodeCannotProvideBundleJob = !Boolean.TRUE.equals(node.getEnabled())
        || EmptyChecks.isNull(jobType)
        || !jobType.isBundle();
    if (nodeCannotProvideBundleJob) {
      return;
    }
    Map<String, Object> effectiveParams = new LinkedHashMap<>();
    if (EmptyChecks.isNotNull(job.defaultParams())) {
      effectiveParams.putAll(job.defaultParams());
    }
    if (EmptyChecks.isNotBlank(node.getNodeParams())) {
      String nodeParamsJson = node.getNodeParams();
      Object nodeParams = JsonUtils.fromJson(nodeParamsJson, Object.class);
      if (nodeParams instanceof Map<?, ?> params) {
        // 参数来自 JSON 对象，键只能为字符串；逐项复制保留原值和覆盖顺序。
        for (Map.Entry<?, ?> parameter : params.entrySet()) {
          String parameterName = (String) parameter.getKey();
          effectiveParams.put(parameterName, parameter.getValue());
        }
      }
    }
    // 与子节点启动一致：Job 默认参数 < 节点默认参数 < 显式根请求参数。
    if (EmptyChecks.isNotNull(request.params())) {
      effectiveParams.putAll(request.params());
    }
    for (BundlePlanParams.BundleFile bundleFile :
        BundlePlanParams.extract(effectiveParams, jobType)) {
      relatedFileReferenceValidator.validate(request.tenantId(), bundleFile.sourceFileId());
    }
  }

  private WorkflowDefinitionEntity loadAdmissibleDefinition(LaunchRequest request) {
    WorkflowDefinitionEntity enabled = workflowDefinitionMapper.selectByTenantAndIdAndEnabled(
        request.tenantId(), request.workflowDefinitionId(), true);
    if (EmptyChecks.isNotNull(enabled)) {
      return enabled;
    }
    // 完整停用只例外接纳已有 misfire；不能凭 CATCH_UP 类型绕过执行准入。
    boolean requestIsNotPersistedMisfireRecovery = request.triggerType() != TriggerType.CATCH_UP
        || EmptyChecks.isNull(request.dataIntervalStart())
        || !triggerRequestMapper.isPersistedMisfireRecovery(
            request.tenantId(),
            request.workflowDefinitionId(),
            request.requestId(),
            request.dataIntervalStart());
    if (requestIsNotPersistedMisfireRecovery) {
      return null;
    }
    return workflowDefinitionMapper.selectByTenantAndIdAndEnabled(
        request.tenantId(), request.workflowDefinitionId(), false);
  }

  /** 定时 Quartz 触发固定使用已同步到 JobDataMap 的 Workflow 版本。 */
  private boolean scheduledWorkflowVersionMatches(
      LaunchRequest request, WorkflowDefinitionEntity definition) {
    Object rawVersion =
        EmptyChecks.isNull(request.params()) ? null : request.params().get("workflowVersion");
    if (EmptyChecks.isNull(rawVersion)) {
      return true;
    }
    try {
      int requestedVersion = Integer.parseInt(rawVersion.toString());
      return EmptyChecks.isNotNull(definition.version())
          && requestedVersion == definition.version();
    } catch (NumberFormatException invalidVersion) {
      return false;
    }
  }

  private record RunPreparation(
      LaunchRequest request,
      LaunchRequest originalRequest,
      TriggerRequestEntity triggerRequest,
      WorkflowDefinitionEntity definition,
      String traceId,
      WorkflowRunReplaySnapshotResolver.Snapshot replaySnapshot,
      WorkflowLaunchWaitSnapshot capacitySnapshot) {}

  private WorkflowRunEntity prepareRunWithSerializationRetry(RunPreparation preparation) {
    // PostgreSQL 的 REPEATABLE READ 在等待同一请求的行锁后可能以 40001 结束事务。
    // 只重试已回滚的准入事务，让下一次快照读取先完成的同一请求；其他约束错误继续上抛。
    for (int attempt = 1; attempt <= 3; attempt++) {
      try {
        return prepareRun(preparation);
      } catch (DataAccessException databaseFailure) {
        if (!JdbcExceptionSupport.hasSqlState(databaseFailure, "40001") || attempt == 3) {
          throw databaseFailure;
        }
      }
    }
    throw new IllegalStateException("unreachable workflow launch retry state");
  }

  private WorkflowRunEntity prepareRun(RunPreparation preparation) {
    LaunchRequest request = preparation.request();
    LaunchRequest deferredRequest = preparation.originalRequest();
    TriggerRequestEntity triggerRequest = preparation.triggerRequest();
    WorkflowDefinitionEntity definition = preparation.definition();
    String traceId = preparation.traceId();
    WorkflowRunReplaySnapshotResolver.Snapshot replaySnapshot = preparation.replaySnapshot();
    WorkflowLaunchWaitSnapshot capacitySnapshot = preparation.capacitySnapshot();
    WorkflowRunEntity existing = workflowRunMapper.selectByTenantAndTriggerRequestId(
        request.tenantId(), triggerRequest.getId());
    if (EmptyChecks.isNotNull(existing)) {
      return existing;
    }
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    try {
      return transaction.execute(ignored -> {
        replayOperationMapper.setTenantContext(request.tenantId());
        WorkflowRunEntity concurrent = workflowRunMapper.selectByTenantAndTriggerRequestId(
            request.tenantId(), triggerRequest.getId());
        if (EmptyChecks.isNotNull(concurrent)) {
          return concurrent;
        }
        TriggerRequestEntity pending = triggerRequestMapper.selectByTenantAndRequestIdForUpdate(
            request.tenantId(), request.requestId());
        boolean pendingRequestDoesNotMatchLaunch = EmptyChecks.isNull(pending)
            || !LaunchRequestIdentityGuard.matches(request, pending)
            || !(TriggerRequestStatus.ACCEPTED.code().equals(pending.getRequestStatus())
                || TriggerRequestStatus.PROCESSING.code().equals(pending.getRequestStatus()));
        if (pendingRequestDoesNotMatchLaunch) {
          throw BizException.of(ResultCode.STATE_CONFLICT, "error.workflow.launch_not_pending");
        }
        WorkflowDefinitionEntity transactionDefinition = definition;
        List<DagNodeResolution> initialNodes;
        if (EmptyChecks.isNotNull(capacitySnapshot)) {
          // 请求锁持续持有到根运行插入；等待终态不得被迟到恢复复活。
          WorkflowLaunchWaitSnapshot lockedSnapshot =
              waitingLaunchService.findSnapshot(deferredRequest);
          if (EmptyChecks.isNull(lockedSnapshot)) {
            throw BizException.of(ResultCode.STATE_CONFLICT, "error.workflow.launch_not_pending");
          }
          ReplayOperationEntity currentOperation =
              replayOperationMapper.selectByTargetRequest(request.tenantId(), request.requestId());
          boolean approvedCapacityReplay =
              requireCapacityReplayAuthorization(request, capacitySnapshot, currentOperation);
          if (!approvedCapacityReplay) {
            transactionDefinition = loadAdmissibleDefinition(request);
            if (EmptyChecks.isNull(transactionDefinition)) {
              throw BizException.of(ResultCode.NOT_FOUND, "error.workflow.definition_not_found");
            }
          }
          initialNodes = initialNodesFromSnapshot(capacitySnapshot.executionSnapshotJson());
          if (EmptyChecks.isEmpty(initialNodes)) {
            throw WorkflowLaunchWaitSnapshot.invalidSnapshot();
          }
        } else if (EmptyChecks.isNull(replaySnapshot)) {
          transactionDefinition = loadAdmissibleDefinition(request);
          if (EmptyChecks.isNull(transactionDefinition)
              || !Objects.equals(definition.version(), transactionDefinition.version())) {
            throw BizException.of(
                ResultCode.STATE_CONFLICT, "error.workflow.definition_changed_during_launch");
          }
          if (!scheduledWorkflowVersionMatches(request, transactionDefinition)) {
            throw BizException.of(
                ResultCode.STATE_CONFLICT, "error.workflow.scheduled_version_stale");
          }
          WorkflowValidationResult validation =
              workflowGraphValidator.validate(transactionDefinition.id());
          if (validation.hasErrors()) {
            throw BizException.of(ResultCode.BUSINESS_ERROR, "error.workflow.definition_invalid");
          }
          initialNodes = initialNodes(request);
        } else {
          if (!Objects.equals(request.workflowDefinitionId(), replaySnapshot.workflowDefinitionId())
              || !Objects.equals(request.bizDate(), replaySnapshot.bizDate())) {
            throw BizException.of(
                ResultCode.STATE_CONFLICT, "error.workflow.execution_snapshot_invalid");
          }
          initialNodes = initialNodesFromSnapshot(replaySnapshot.executionSnapshotJson());
          if (EmptyChecks.isEmpty(initialNodes)) {
            throw BizException.of(
                ResultCode.STATE_CONFLICT, "error.workflow.execution_snapshot_invalid");
          }
        }
        Instant startedAt = BatchDateTimeSupport.utcNow();
        WorkflowRunEntity created = new WorkflowRunEntity();
        created.setTenantId(request.tenantId());
        created.setWorkflowDefinitionId(request.workflowDefinitionId());
        created.setSingleJobDefinitionId(
            EmptyChecks.isNotNull(capacitySnapshot)
                ? capacitySnapshot.singleJobDefinitionId()
                : EmptyChecks.isNull(replaySnapshot)
                    ? transactionDefinition.singleJobDefinitionId()
                    : replaySnapshot.singleJobDefinitionId());
        created.setTriggerRequestId(triggerRequest.getId());
        created.setWorkflowVersionSnapshot(
            EmptyChecks.isNotNull(capacitySnapshot)
                ? capacitySnapshot.workflowVersion()
                : EmptyChecks.isNull(replaySnapshot)
                    ? transactionDefinition.version()
                    : replaySnapshot.workflowVersion());
        created.setBizDate(request.bizDate());
        created.setDataIntervalStart(request.dataIntervalStart());
        created.setDataIntervalEnd(request.dataIntervalEnd());
        created.setExecutionSnapshot(
            EmptyChecks.isNotNull(capacitySnapshot)
                ? capacitySnapshot.executionSnapshotJson()
                : EmptyChecks.isNull(replaySnapshot)
                    ? buildExecutionSnapshot(request, transactionDefinition, initialNodes)
                    : replaySnapshot.executionSnapshotJson());
        created.setRunStatus(WorkflowRunStatus.CREATED.code());
        String currentNodeCode;
        if (EmptyChecks.isEmpty(initialNodes)) {
          currentNodeCode = WorkflowNodeCode.START.code();
        } else {
          List<String> initialNodeCodes =
              initialNodes.stream().map(DagNodeResolution::nodeCode).toList();
          currentNodeCode = String.join(",", initialNodeCodes);
        }
        created.setCurrentNodeCode(currentNodeCode);
        created.setTraceId(traceId);
        created.setDryRun(request.dryRun());
        WorkflowLaunchWaitSnapshot snapshotForWait = EmptyChecks.isNotNull(capacitySnapshot)
            ? capacitySnapshot
            : WorkflowLaunchWaitSnapshot.capture(deferredRequest, request, created);
        int inserted = workflowRunMapper.insertIfActiveSlotFree(created);
        if (inserted == 0) {
          // 容量冲突不回滚准入快照；请求与密文等待必须在当前事务一起提交。
          waitingLaunchService.defer(deferredRequest, snapshotForWait);
          return null;
        }
        attachReplayOperationTarget(request, created, startedAt);
        WorkflowNodeRunEntity start = new WorkflowNodeRunEntity();
        start.setWorkflowRunId(created.getId());
        start.setNodeCode(WorkflowNodeCode.START.code());
        start.setNodeType(WorkflowNodeType.START.code());
        start.setRunSeq(1);
        start.setNodeStatus(WorkflowNodeRunStatus.SUCCESS.code());
        start.setRetryCount(0);
        start.setDurationMs(0L);
        start.setStartedAt(startedAt);
        start.setFinishedAt(startedAt);
        String serializedLaunchParams = serializeLaunchParams(request);
        String protectedLaunchParams = secretPayloadProtector.protect(serializedLaunchParams);
        start.setSourcePayloadProtected(protectedLaunchParams);
        workflowNodeRunMapper.insert(start);
        return created;
      });
    } catch (DuplicateKeyException duplicate) {
      WorkflowRunEntity winner = workflowRunMapper.selectByTenantAndTriggerRequestId(
          request.tenantId(), triggerRequest.getId());
      if (EmptyChecks.isNotNull(winner)) {
        return winner;
      }
      throw duplicate;
    } catch (BizException failure) {
      rejectIfUnprepared(request);
      throw failure;
    }
  }

  private void dispatchInitialNodes(LaunchRequest request, WorkflowRunEntity run, String traceId) {
    if (WorkflowRunStatus.terminalCodes().contains(run.getRunStatus())
        || WorkflowRunStatus.PAUSED.code().equals(run.getRunStatus())) {
      return;
    }
    List<DagNodeResolution> initialNodes = initialNodesFromSnapshot(run.getExecutionSnapshot());
    if (EmptyChecks.isEmpty(initialNodes)) {
      reject(request);
      throw BizException.of(ResultCode.BUSINESS_ERROR, "error.workflow.no_initial_nodes");
    }
    WorkflowNodeRunEntity start = workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(
        run.getId(), WorkflowNodeCode.START.code());
    if (EmptyChecks.isNull(start)) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.workflow.execution_snapshot_invalid");
    }
    String protectedPayload = start.getSourcePayloadProtected();
    if (EmptyChecks.isBlank(protectedPayload)) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.workflow.execution_snapshot_invalid");
    }
    String payload = secretPayloadProtector.reveal(protectedPayload);
    String tenantId = request.tenantId();
    Long workflowRunId = run.getId();
    String runningStatus = WorkflowRunStatus.RUNNING.code();
    String initialNodeCodes =
        String.join(",", initialNodes.stream().map(DagNodeResolution::nodeCode).toList());
    Instant runningAt = BatchDateTimeSupport.utcNow();
    WorkflowRunMarkRunningParam workflowRunMarkRunningParam = WorkflowRunMarkRunningParam.builder()
        .tenantId(tenantId)
        .id(workflowRunId)
        .runStatus(runningStatus)
        .currentNodeCode(initialNodeCodes)
        .startedAt(runningAt)
        .build();
    int markedRunning = workflowRunMapper.markRunning(workflowRunMarkRunningParam);
    if (markedRunning <= 0) {
      return;
    }
    run.setRunStatus(WorkflowRunStatus.RUNNING.code());
    for (DagNodeResolution node : initialNodes) {
      workflowNodeDispatchService.dispatchNode(null, run, node, payload, traceId);
    }
    workflowDagService.cascadeSkipDownstreamForRun(run, WorkflowNodeCode.START.code(), payload);
    // START 的未命中分支完成跳过后，再检查完整冻结图；终态与事件同事务提交。
    TransactionTemplate completionTransaction = new TransactionTemplate(transactionManager);
    completionTransaction.executeWithoutResult(
        status -> terminalFinalizer.finalizeIfAllNodesTerminal(run));
  }

  private List<DagNodeResolution> initialNodes(LaunchRequest request) {
    String serializedParams = serializeLaunchParams(request);
    return workflowDagService.resolveInitialNodes(request.workflowDefinitionId(), serializedParams);
  }

  private String serializeLaunchParams(LaunchRequest request) {
    Map<String, Object> launchParams =
        EmptyChecks.isNull(request.params()) ? Map.of() : request.params();
    return JsonUtils.toJson(launchParams);
  }

  private String buildExecutionSnapshot(
      LaunchRequest request,
      WorkflowDefinitionEntity definition,
      List<DagNodeResolution> initialNodes) {
    List<WorkflowNodeEntity> nodes =
        workflowNodeMapper.selectByWorkflowDefinitionId(definition.id());
    List<WorkflowEdgeEntity> edges =
        workflowEdgeMapper.selectAllByWorkflowDefinitionId(definition.id());
    Map<String, Object> jobVersions = new LinkedHashMap<>();
    for (WorkflowNodeEntity node : nodes) {
      if (!WorkflowNodeType.JOB.code().equals(node.getNodeType())
          || EmptyChecks.isNull(node.getRelatedJobCode())) {
        continue;
      }
      JobDefinitionEntity job =
          configCacheService.findEnabledJobDefinition(request.tenantId(), node.getRelatedJobCode());
      if (EmptyChecks.isNull(job)) {
        reject(request);
        throw BizException.of(ResultCode.NOT_FOUND, "error.job.definition_not_found");
      }
      job = rerunJobDefinitionResolver.resolve(request, node.getRelatedJobCode(), job);
      validateBundleFileReferences(request, node, job);
      Map<String, Object> identity = new LinkedHashMap<>();
      identity.put("jobDefinitionId", job.id());
      identity.put("jobCode", job.jobCode());
      identity.put("jobType", job.jobType());
      identity.put("jobDefinitionVersion", job.version());
      SystemPipelineCatalog.forJobType(job.jobType()).ifPresent(pipeline -> {
        identity.put("systemPipelineCode", pipeline.code());
        identity.put("systemPipelineVersion", pipeline.semanticVersion());
      });
      identity.put("executionDefinition", job);
      jobVersions.put(node.getNodeCode(), identity);
    }
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("schemaVersion", 2);
    snapshot.put("workflowDefinitionId", definition.id());
    snapshot.put("workflowVersion", definition.version());
    snapshot.put("workflowCalendarCode", definition.calendarCode());
    snapshot.put("triggerRequestId", request.requestId());
    snapshot.put("replaySessionId", request.replaySessionId());
    snapshot.put("nodes", nodes);
    snapshot.put("edges", EmptyChecks.isNull(edges) ? List.of() : edges);
    snapshot.put("jobDefinitions", jobVersions);
    snapshot.put(
        "initialNodes",
        initialNodes.stream()
            .map(node -> Map.of("nodeCode", node.nodeCode(), "nodeType", node.nodeType()))
            .toList());
    return JsonUtils.toJson(snapshot);
  }

  private List<DagNodeResolution> initialNodesFromSnapshot(String executionSnapshot) {
    Map<String, Object> snapshot =
        JsonUtils.fromJson(executionSnapshot, new TypeReference<Map<String, Object>>() {});
    Object rawInitialNodes = snapshot.get("initialNodes");
    if (!(rawInitialNodes instanceof List<?> items)) {
      return List.of();
    }
    List<DagNodeResolution> nodes = new ArrayList<>(items.size());
    for (Object item : items) {
      if (!(item instanceof Map<?, ?> node)) {
        continue;
      }
      Object code = node.get("nodeCode");
      Object type = node.get("nodeType");
      if (code instanceof String nodeCode && type instanceof String nodeType) {
        nodes.add(new DagNodeResolution(nodeCode, nodeType));
      }
    }
    return List.copyOf(nodes);
  }

  private void validateRequest(LaunchRequest request) {
    if (EmptyChecks.isNull(request)) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.request_body_required");
    }
    String tenantId = request.tenantId();
    String requestId = request.requestId();
    Long workflowDefinitionId = request.workflowDefinitionId();
    boolean requiredLaunchFieldsAreInvalid = EmptyChecks.isBlank(tenantId)
        || EmptyChecks.isBlank(requestId)
        || EmptyChecks.isNull(workflowDefinitionId)
        || workflowDefinitionId <= 0
        || EmptyChecks.isNull(request.bizDate());
    if (requiredLaunchFieldsAreInvalid) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.request_body_required");
    }
    boolean dataIntervalIsInvalid = EmptyChecks.isNotNull(request.dataIntervalStart())
        && EmptyChecks.isNotNull(request.dataIntervalEnd())
        && !request.dataIntervalStart().isBefore(request.dataIntervalEnd());
    if (dataIntervalIsInvalid) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.request_body_required");
    }
  }

  /** 与建档共用请求行锁，避免过时的准入检查拒绝已创建的运行。 */
  private void rejectIfUnprepared(LaunchRequest request) {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.executeWithoutResult(ignored -> {
      TriggerRequestEntity locked = triggerRequestMapper.selectByTenantAndRequestIdForUpdate(
          request.tenantId(), request.requestId());
      if (EmptyChecks.isNull(locked) || !LaunchRequestIdentityGuard.matches(request, locked)) {
        return;
      }
      WorkflowRunEntity run =
          workflowRunMapper.selectByTenantAndTriggerRequestId(request.tenantId(), locked.getId());
      if (EmptyChecks.isNull(run)) {
        reject(request);
      } else {
        throw new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "workflow launch is in progress; retry the same request");
      }
    });
  }

  private void reject(LaunchRequest request) {
    triggerRequestMapper.updateAcceptance(
        request.tenantId(), request.requestId(), "REJECTED", null);
    TransactionTemplate replayOperationTransaction = new TransactionTemplate(transactionManager);
    replayOperationTransaction.executeWithoutResult(ignored -> {
      replayOperationMapper.setTenantContext(request.tenantId());
      ReplayOperationEntity operation =
          replayOperationMapper.selectByTargetRequest(request.tenantId(), request.requestId());
      if (EmptyChecks.isNotNull(operation)) {
        ReplayOperationWorkflowOutcomeParam outcome = ReplayOperationWorkflowOutcomeParam.builder()
            .tenantId(request.tenantId())
            .requestId(request.requestId())
            .status(ReplayOperationStatus.FAILED.code())
            .succeededCount(0)
            .failedCount(1)
            .inFlightCount(0)
            .now(BatchDateTimeSupport.utcNow())
            .build();
        replayOperationMapper.updateWorkflowTargetOutcome(outcome);
      }
    });
  }

  private ReplayOperationEntity findReplayOperation(LaunchRequest request) {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setReadOnly(true);
    return transaction.execute(ignored -> {
      replayOperationMapper.setTenantContext(request.tenantId());
      return replayOperationMapper.selectByTargetRequest(request.tenantId(), request.requestId());
    });
  }

  private void attachReplayOperationTarget(
      LaunchRequest request, WorkflowRunEntity workflowRun, Instant attachedAt) {
    ReplayOperationEntity operation =
        replayOperationMapper.selectByTargetRequest(request.tenantId(), request.requestId());
    if (EmptyChecks.isNull(operation)) {
      return;
    }
    int attached = replayOperationMapper.attachTargetWorkflowRun(
        request.tenantId(), request.requestId(), workflowRun.getId(), attachedAt);
    if (attached != 1) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.workflow.replay_target_conflict");
    }
  }
}
