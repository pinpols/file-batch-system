package io.github.pinpols.batch.orchestrator.service;

import io.github.pinpols.batch.common.dto.DependencyResultReference;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.dto.ReplayExecutionGuard;
import io.github.pinpols.batch.common.enums.DictEnum;
import io.github.pinpols.batch.common.enums.FailureClass;
import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.enums.JobType;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.ResultVersionPolicy;
import io.github.pinpols.batch.common.enums.TriggerRequestStatus;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.logging.AuditLogConstants;
import io.github.pinpols.batch.common.logging.LogSanitizer;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.common.utils.IdGenerator;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.application.engine.JobTerminalOutboxService;
import io.github.pinpols.batch.orchestrator.application.plan.BundlePlanParams;
import io.github.pinpols.batch.orchestrator.application.service.failure.PersistenceConflictDetectionPort;
import io.github.pinpols.batch.orchestrator.application.service.governance.RelatedFileReferenceValidator;
import io.github.pinpols.batch.orchestrator.application.service.task.OrchestratorJobMappers;
import io.github.pinpols.batch.orchestrator.application.service.task.PartitionDispatchService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.OrchestratorWorkflowMappers;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowDagService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunExecutionSnapshot;
import io.github.pinpols.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateInstanceProgressParam;
import io.github.pinpols.batch.orchestrator.domain.view.JobDefinitionView;
import io.github.pinpols.batch.orchestrator.mapper.JobExecutionLogMapper;
import io.github.pinpols.batch.orchestrator.observability.LaunchPhaseMetrics;
import io.github.pinpols.batch.orchestrator.observability.LaunchPhaseMetrics.Phase;
import io.github.pinpols.batch.orchestrator.service.LaunchValidationService.LaunchLoadResult;
import io.micrometer.observation.annotation.Observed;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 批任务启动（Launch）的核心入口：把一次触发请求落地为可调度的运行态，并驱动后续分片/任务派发。
 *
 * <p>这里把 launch 拆成两段<strong>独立提交</strong>的事务（T1/T2），目的是降低锁竞争与提升可重试性：
 *
 * <ul>
 *   <li><strong>T1（准备态写入数据库）</strong>：只创建 {@code job_instance}/{@code workflow_run} 以及 START
 *       节点的运行态， 快速提交，作为后续调度/派发的"事实源"。
 *   <li><strong>T2（运行态构建与派发）</strong>：创建 partition/task、写 outbox，并推进 instance/workflow 状态。 高竞争表只在
 *       T2 短事务里触碰，避免长事务持锁。
 * </ul>
 *
 * <p>事务边界通过显式 {@link TransactionTemplate} 表达，避免核心启动链依赖自身代理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultLaunchService implements LaunchService {

  private final LaunchValidationService launchValidationService;
  private final PartitionDispatchService partitionDispatchService;
  private final OrchestratorJobMappers jobMappers;
  private final OrchestratorWorkflowMappers workflowMappers;
  private final LaunchBatchDayService launchBatchDayService;
  private final BatchDayGateService batchDayGateService;
  private final JobDependencyWaitingLaunchService jobDependencyWaitingLaunchService;
  private final LaunchParamResolver launchParamResolver;
  private final RelatedFileReferenceValidator relatedFileReferenceValidator;
  private final DispatchSecretSnapshotResolver dispatchSecretSnapshotResolver;
  private final DispatchChannelSnapshotService dispatchChannelSnapshotService;
  private final FileTemplateSnapshotService fileTemplateSnapshotService;
  private final JobExecutionLogMapper jobExecutionLogMapper;
  private final PlatformTransactionManager transactionManager;
  private final LaunchPhaseMetrics launchPhaseMetrics;
  private final PersistenceConflictDetectionPort persistenceConflictDetection;
  private final JobTerminalOutboxService terminalOutbox;

  @Override
  @Observed(name = "orch.launch", contextualName = "orch.launch")
  public LaunchResponse launch(LaunchRequest request) {
    LaunchLoadResult loaded =
        launchPhaseMetrics.record(Phase.VALIDATION, () -> launchValidationService.load(request));
    LaunchResponse duplicateShortCircuit = maybeShortCircuitDuplicate(request, loaded);
    if (duplicateShortCircuit != null) {
      attachJobInstanceToWorkflowNodeInNewTransaction(loaded, loaded.existingInstance());
      return duplicateShortCircuit;
    }
    String traceId = resolveTraceId(request);
    LaunchRequest routedRequest = launchBatchDayService.routeLateArrivalIfNeeded(request, loaded);
    Map<String, Object> effectiveParams =
        launchParamResolver.mergeLaunchParams(loaded.jobDefinition(), routedRequest);
    validateBundleParamsBeforeInstanceCreation(request, loaded, effectiveParams);
    validateRelatedFileReference(request, effectiveParams);
    validateParentInstanceReference(request, effectiveParams);
    BatchDayGateService.GateDecision gateDecision =
        batchDayGateService.evaluateAndApply(routedRequest, loaded, effectiveParams, traceId);
    if (!gateDecision.allowed()) {
      return LaunchResponse.skipped(traceId);
    }
    Optional<List<DependencyResultReference>> dependencySnapshot =
        jobDependencyWaitingLaunchService.captureOrDefer(routedRequest);
    if (!dependencySnapshot.isPresent()) {
      return LaunchResponse.skipped(traceId);
    }
    LaunchRequest launchRequestWithDependencySnapshot = routedRequest.toBuilder()
        .dependencyResultSnapshot(dependencySnapshot.get())
        .build();
    // T1：先把 instance/workflow 写入数据库并提交，避免 T2 执行期间持有更长时间锁。
    PreparedLaunch prepared;
    try {
      prepared = launchPhaseMetrics.record(
          Phase.PREPARE_TRANSACTION,
          () -> prepareJobInstanceInTransaction(
              launchRequestWithDependencySnapshot, loaded, effectiveParams, traceId));
    } catch (TemplateCaptureRejected rejection) {
      jobMappers.triggerRequestMapper.updateAcceptance(
          request.tenantId(), request.requestId(), TriggerRequestStatus.REJECTED.code(), null);
      throw rejection.businessFailure;
    } catch (DataIntegrityViolationException exception) {
      SwallowedExceptionLogger.info(
          DefaultLaunchService.class, "catch:DataIntegrityViolationException", exception);

      // DuplicateKeyException extends DataIntegrityViolationException, covered here.
      return resolveConcurrentDuplicate(request, loaded, exception);
    } catch (RuntimeException exception) {
      // PG 唯一约束等可能被包装为 TransactionSystemException / UncategorizedDataAccess 等，需沿 cause 识别
      // 23505
      if (persistenceConflictDetection.isUniqueConstraintViolation(
          exception, "uk_job_instance_tenant_dedup")) {
        return resolveConcurrentDuplicate(request, loaded, exception);
      }
      throw exception;
    }

    launchPhaseMetrics.record(
        Phase.DISPATCH_TRANSACTION,
        () -> dispatchAndMarkLaunched(
            request, prepared.effectiveParams(), traceId, prepared, loaded.jobDefinition()));
    return new LaunchResponse(prepared.jobInstance().getInstanceNo(), traceId);
  }

  private void validateBundleParamsBeforeInstanceCreation(
      LaunchRequest request, LaunchLoadResult loaded, Map<String, Object> effectiveParams) {
    JobType jobType = DictEnum.fromCode(JobType.class, loaded.jobDefinition().jobType());
    try {
      BundlePlanParams.validate(effectiveParams, jobType);
      for (BundlePlanParams.BundleFile bundleFile :
          BundlePlanParams.extract(effectiveParams, jobType)) {
        relatedFileReferenceValidator.validate(request.tenantId(), bundleFile.sourceFileId());
      }
    } catch (BizException ex) {
      jobMappers.triggerRequestMapper.updateAcceptance(
          request.tenantId(), request.requestId(), TriggerRequestStatus.REJECTED.code(), null);
      throw ex;
    }
  }

  private void validateRelatedFileReference(
      LaunchRequest request, Map<String, Object> effectiveParams) {
    Long relatedFileId = launchParamResolver.resolveRelatedFileId(effectiveParams);
    try {
      relatedFileReferenceValidator.validate(request.tenantId(), relatedFileId);
    } catch (BizException exception) {
      jobMappers.triggerRequestMapper.updateAcceptance(
          request.tenantId(), request.requestId(), TriggerRequestStatus.REJECTED.code(), null);
      throw exception;
    }
  }

  private void validateParentInstanceReference(
      LaunchRequest request, Map<String, Object> effectiveParams) {
    Long parentInstanceId = launchParamResolver.resolveParentInstanceId(effectiveParams);
    if (EmptyChecks.isNull(parentInstanceId)) {
      return;
    }
    JobInstanceEntity parentInstance =
        jobMappers.jobInstanceMapper.selectById(request.tenantId(), parentInstanceId);
    if (EmptyChecks.isNotNull(parentInstance)
        && request.tenantId().equals(parentInstance.getTenantId())) {
      return;
    }
    jobMappers.triggerRequestMapper.updateAcceptance(
        request.tenantId(), request.requestId(), TriggerRequestStatus.REJECTED.code(), null);
    throw BizException.of(ResultCode.NOT_FOUND, "error.job.instance_not_found");
  }

  /**
   * RERUN 语义：哪怕 dedup_key 命中现有实例，也要新建一条 run_attempt = max+1 的实例， 并把 parent_instance_id 指回上一次尝试；只有非
   * RERUN 触发类型才沿用旧的幂等短路。
   */
  private LaunchResponse maybeShortCircuitDuplicate(
      LaunchRequest request, LaunchLoadResult loaded) {
    boolean rerunMode = request.triggerType() == TriggerType.RERUN;
    if (loaded.existingInstance() == null || rerunMode) {
      return null;
    }
    // 同一 Kafka request 的重投可能落在 T1(实例已提交)与 T2(派发尚未完成)之间。此时已有
    // job_instance 不是新的业务去重，若改写成 DUPLICATE 会遮蔽 T2 失败并阻断 CREATED 恢复器。
    if (isOriginalRequestReplay(loaded)) {
      return new LaunchResponse(
          loaded.existingInstance().getInstanceNo(), loaded.existingInstance().getTraceId());
    }
    int updated = jobMappers.triggerRequestMapper.updateAcceptance(
        request.tenantId(),
        request.requestId(),
        TriggerRequestStatus.DUPLICATE.code(),
        loaded.existingInstance().getId());
    if (updated == 0) {
      log.warn(
          "updateAcceptance(DUPLICATE) affected 0 rows; the row is already terminal: tenantId={} requestId={}",
          LogSanitizer.value(request.tenantId()),
          LogSanitizer.value(request.requestId()));
    }
    return new LaunchResponse(
        loaded.existingInstance().getInstanceNo(), loaded.existingInstance().getTraceId());
  }

  private String resolveTraceId(LaunchRequest request) {
    return request.traceId() == null || request.traceId().isBlank()
        ? IdGenerator.newTraceId()
        : request.traceId();
  }

  /** T2：构建分片/任务/outbox，并推进运行态；该事务可在失败后独立重试。 */
  private void dispatchAndMarkLaunched(
      LaunchRequest request,
      Map<String, Object> effectiveParams,
      String traceId,
      PreparedLaunch prepared,
      JobDefinitionView executionDefinition) {
    try {
      PartitionDispatchService.DispatchRequest dispatchRequest =
          new PartitionDispatchService.DispatchRequest(
              request, effectiveParams, traceId, executionDefinition);
      PartitionDispatchService.DispatchRuntime dispatchRuntime =
          new PartitionDispatchService.DispatchRuntime(
              prepared.jobInstance(),
              prepared.workflowRun(),
              prepared.initialNodes(),
              prepared.startedAt());
      PartitionDispatchService.DispatchContext dispatchContext =
          PartitionDispatchService.DispatchContext.of(dispatchRequest, dispatchRuntime);
      partitionDispatchService.dispatch(dispatchContext);
    } catch (RuntimeException ex) {
      finalizeJobInstanceOnDispatchBusinessError(request, prepared.jobInstance(), ex);
      throw ex;
    }

    int updated = jobMappers.triggerRequestMapper.updateAcceptance(
        request.tenantId(),
        request.requestId(),
        TriggerRequestStatus.LAUNCHED.code(),
        prepared.jobInstance().getId());
    if (updated == 0) {
      log.warn(
          "updateAcceptance(LAUNCHED) affected 0 rows; the row is already terminal: tenantId={} requestId={}",
          LogSanitizer.value(request.tenantId()),
          LogSanitizer.value(request.requestId()));
    }
  }

  private void finalizeJobInstanceOnDispatchBusinessError(
      LaunchRequest request, JobInstanceEntity jobInstance, RuntimeException exception) {
    BizException dispatchFailure = findPartitionDispatchBusinessError(exception);
    if (dispatchFailure == null || jobInstance == null || jobInstance.getId() == null) {
      return;
    }
    try {
      finalizeDispatchFailure(request, jobInstance, dispatchFailure);
    } catch (RuntimeException reverseEx) {
      SwallowedExceptionLogger.info(
          DefaultLaunchService.class, "catch:finalizeJobInstanceOnDispatchFailure", reverseEx);
    }
  }

  private BizException findPartitionDispatchBusinessError(RuntimeException exception) {
    Throwable current = exception;
    while (current != null) {
      if (current instanceof BizException bizException
          && "error.partition.dispatch_business_error".equals(bizException.getMessageKey())) {
        return bizException;
      }
      current = current.getCause();
    }
    return null;
  }

  /** T2 fail-fast 后把 job_instance CREATED → FAILED,避免长期停留在无 task 的不可执行状态。 */
  @Override
  public void finalizeDispatchFailure(
      LaunchRequest request, JobInstanceEntity jobInstance, BizException dispatchFailure) {
    if (EmptyChecks.isNull(jobInstance)
        || EmptyChecks.isNull(jobInstance.getId())
        || EmptyChecks.isNull(dispatchFailure)) {
      return;
    }
    inNewTransaction(
        () -> markJobInstanceFailedDueToDispatch(request, jobInstance, dispatchFailure));
  }

  private void markJobInstanceFailedDueToDispatch(
      LaunchRequest request, JobInstanceEntity jobInstance, BizException dispatchFailure) {
    Long expectedVersion = jobInstance.getVersion() == null ? 0L : jobInstance.getVersion();
    Instant now = BatchDateTimeSupport.utcNow();
    UpdateInstanceProgressParam.UpdateInstanceProgressParamBuilder progressUpdate =
        UpdateInstanceProgressParam.builder()
            .tenantId(jobInstance.getTenantId())
            .id(jobInstance.getId())
            .instanceStatus(
                Boolean.TRUE.equals(jobInstance.getDryRun())
                    ? JobInstanceStatus.FAILED_DRY_RUN.code()
                    : JobInstanceStatus.FAILED.code())
            .successPartitionCount(0)
            .failedPartitionCount(0)
            .cancelledPartitionCount(0)
            .terminatedPartitionCount(0);
    String dispatchRejectSummary = buildDispatchRejectSummary(dispatchFailure);
    int rows = jobMappers.jobInstanceMapper.updateProgress(progressUpdate
        .resultSummary(dispatchRejectSummary)
        .finishedAt(now)
        .expectedVersion(expectedVersion)
        .failureClass(FailureClass.BUSINESS_RULE.code())
        .build());
    if (rows > 0) {
      terminalOutbox.writeTerminalEvent(jobInstance.getTenantId(), jobInstance.getId());
      int updated = jobMappers.triggerRequestMapper.updateAcceptance(
          request.tenantId(),
          request.requestId(),
          TriggerRequestStatus.REJECTED.code(),
          jobInstance.getId());
      if (updated == 0) {
        log.warn(
            "updateAcceptance(REJECTED) affected 0 rows; the row is already terminal: tenantId={} requestId={}",
            LogSanitizer.value(request.tenantId()),
            LogSanitizer.value(request.requestId()));
      }
      appendDispatchRejectedAudit(jobInstance, dispatchFailure);
    }
  }

  private String buildDispatchRejectSummary(BizException dispatchFailure) {
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("errorCode", "DISPATCH_REJECTED");
    summary.put("messageKey", dispatchFailure.getMessageKey());
    summary.put("reasonCode", dispatchRejectReasonCode(dispatchFailure));
    summary.put("reason", dispatchRejectReasonMessage(dispatchFailure));
    summary.put("source", "partition_dispatch");
    return JsonUtils.toJson(summary);
  }

  private String dispatchRejectReasonCode(BizException dispatchFailure) {
    Object[] args = dispatchFailure.getMessageArgs();
    if (args == null || args.length < 2 || args[0] == null) {
      return "DISPATCH_REJECTED";
    }
    return String.valueOf(args[0]);
  }

  private String dispatchRejectReasonMessage(BizException dispatchFailure) {
    Object[] args = dispatchFailure.getMessageArgs();
    if (args == null || args.length == 0) {
      return dispatchFailure.getMessage();
    }
    Object messageArg = args.length >= 2 ? args[1] : args[0];
    return messageArg == null ? dispatchFailure.getMessage() : String.valueOf(messageArg);
  }

  private void appendDispatchRejectedAudit(
      JobInstanceEntity jobInstance, BizException dispatchFailure) {
    JobExecutionLogEntity logEntity = new JobExecutionLogEntity();
    logEntity.setTenantId(jobInstance.getTenantId());
    logEntity.setJobInstanceId(jobInstance.getId());
    logEntity.setLogLevel("WARN");
    logEntity.setLogType(AuditLogConstants.LOG_TYPE_AUDIT);
    logEntity.setTraceId(jobInstance.getTraceId());
    logEntity.setMessage("JOB_INSTANCE_DISPATCH_REJECTED");
    logEntity.setDetailRef("job_instance.dispatch_rejected");
    String dispatchRejectSummary = buildDispatchRejectSummary(dispatchFailure);
    logEntity.setExtraJson(dispatchRejectSummary);
    jobExecutionLogMapper.insert(logEntity);
  }

  /**
   * T1 事务：创建 Job 实例准备态。
   *
   * <p>该事务只做"准备态写入数据库"，不触碰高竞争的 task/partition/outbox 表，从而缩短锁持有时间。
   */
  public PreparedLaunch prepareJobInstance(
      LaunchRequest request,
      LaunchLoadResult loaded,
      Map<String, Object> effectiveParams,
      String traceId) {
    ReplayExecutionGuard replayExecutionGuard = resolveReplayExecutionGuard(request, loaded);
    LaunchValidationService.WorkflowReplayExecutionBinding replayBinding =
        loaded.workflowReplayExecutionBinding();
    Map<String, Object> executionParams;
    try {
      Map<String, Object> credentialParams = EmptyChecks.isNull(replayBinding)
          ? dispatchSecretSnapshotResolver.pin(
              request.tenantId(), loaded.jobDefinition().jobType(), effectiveParams)
          : effectiveParams;
      if (EmptyChecks.isNotNull(replayBinding)) {
        if (EmptyChecks.isNull(replayExecutionGuard)
            || !Objects.equals(
                replayBinding.replayOperationId(), replayExecutionGuard.replayOperationId())) {
          throw BizException.of(
              ResultCode.STATE_CONFLICT, "error.workflow.execution_snapshot_invalid");
        }
        Map<String, Object> dispatchParams = dispatchChannelSnapshotService.pinWorkflowReplay(
            request.tenantId(),
            loaded.jobDefinition().jobType(),
            credentialParams,
            replayBinding.protectedDispatchChannelSnapshots(),
            replayBinding.dispatchSecretBindings());
        executionParams = fileTemplateSnapshotService.pinWorkflowReplay(
            request, loaded.jobDefinition(), dispatchParams, replayBinding);
      } else {
        Map<String, Object> dispatchParams = dispatchChannelSnapshotService.pin(
            request.tenantId(), loaded.jobDefinition().jobType(), credentialParams);
        executionParams =
            fileTemplateSnapshotService.pin(request, loaded.jobDefinition(), dispatchParams);
      }
    } catch (BizException rejectedTemplate) {
      // 必须先回滚 T1，再由调用方事务把请求标为拒绝。
      throw new TemplateCaptureRejected(rejectedTemplate);
    }
    Instant batchDaySlaDeadlineAt = launchBatchDayService.resolveBatchDaySlaDeadlineAt(
        request.tenantId(), loaded.effectiveCalendarCode(), request.bizDate());
    JobInstanceEntity jobInstance = buildJobInstanceEntity(
        request, loaded, executionParams, traceId, batchDaySlaDeadlineAt, replayExecutionGuard);
    int inserted = jobMappers.jobInstanceMapper.insert(jobInstance);
    if (inserted <= 0) {
      throw new DuplicateKeyException("duplicate job_instance idempotency key: tenant="
          + request.tenantId()
          + ", dedupKey="
          + jobInstance.getDedupKey()
          + ", runAttempt="
          + jobInstance.getRunAttempt());
    }
    if (!Boolean.TRUE.equals(request.dryRun())) {
      launchBatchDayService.upsertBatchDayInstance(
          request,
          loaded.jobDefinition(),
          loaded.effectiveCalendarCode(),
          executionParams,
          batchDaySlaDeadlineAt);
    }

    Instant startedAt = BatchDateTimeSupport.utcNow();
    return new PreparedLaunch(jobInstance, null, List.of(), startedAt, executionParams);
  }

  private PreparedLaunch prepareJobInstanceInTransaction(
      LaunchRequest request,
      LaunchLoadResult loaded,
      Map<String, Object> effectiveParams,
      String traceId) {
    // 子作业可能参与 Workflow 节点事务；捕获阶段拒绝应回滚到保存点，保留外层失败收敛。
    TransactionTemplate transaction = newTransaction(TransactionDefinition.PROPAGATION_NESTED);
    return transaction.execute(
        status -> preparePendingJobInstance(request, loaded, effectiveParams, traceId));
  }

  private static final class TemplateCaptureRejected extends RuntimeException {

    private final BizException businessFailure;

    private TemplateCaptureRejected(BizException businessFailure) {
      super(businessFailure);
      this.businessFailure = businessFailure;
    }
  }

  private PreparedLaunch preparePendingJobInstance(
      LaunchRequest request,
      LaunchLoadResult loaded,
      Map<String, Object> effectiveParams,
      String traceId) {
    TriggerRequestEntity current =
        jobMappers.triggerRequestMapper.selectByTenantAndRequestIdForUpdate(
            request.tenantId(), request.requestId());
    LaunchRequestIdentityGuard.requirePending(request, current);
    if (!Objects.equals(loaded.triggerRequest().getId(), current.getId())) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT,
          ResultCode.STATE_CONFLICT.detailKey(),
          "Original trigger request changed before Job Instance preparation");
    }
    PreparedLaunch prepared = prepareJobInstance(request, loaded, effectiveParams, traceId);
    attachJobInstanceToWorkflowNode(loaded, prepared.jobInstance());
    return prepared;
  }

  /**
   * 在同一事务中，将异步启动的子 Job 关联到其 Workflow 节点，且与
   * Job Instance 创建一并提交。BatchDayGate WAIT 请求也适用；这些 Job 会稍后由
   * 等待释放调度器启动，而不是通过 ChildJobLaunchSupport 同步调用启动。
   */
  private void attachJobInstanceToWorkflowNodeInNewTransaction(
      LaunchLoadResult loaded, JobInstanceEntity jobInstance) {
    if (!hasWorkflowNodeParent(loaded, jobInstance)) {
      return;
    }
    inNewTransaction(() -> attachJobInstanceToWorkflowNode(loaded, jobInstance));
  }

  private void attachJobInstanceToWorkflowNode(
      LaunchLoadResult loaded, JobInstanceEntity jobInstance) {
    if (!hasWorkflowNodeParent(loaded, jobInstance)) {
      return;
    }
    TriggerRequestEntity triggerRequest = loaded.triggerRequest();
    WorkflowNodeRunEntity nodeRun =
        workflowMappers.workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(
            triggerRequest.getParentWorkflowRunId(), triggerRequest.getParentNodeCode());
    Guard.require(
        EmptyChecks.isNotNull(nodeRun) && EmptyChecks.isNotNull(nodeRun.getId()),
        ResultCode.STATE_CONFLICT,
        "error.workflow.parent_node_launch_invalid");
    int linked = workflowMappers.workflowNodeRunMapper.attachJobInstance(
        triggerRequest.getTenantId(), nodeRun.getId(), jobInstance.getId());
    Guard.require(
        linked > 0, ResultCode.STATE_CONFLICT, "error.workflow.node_job_instance_link_conflict");
  }

  private boolean hasWorkflowNodeParent(LaunchLoadResult loaded, JobInstanceEntity jobInstance) {
    boolean parentLaunchContextIncomplete = EmptyChecks.isNull(loaded)
        || EmptyChecks.isNull(loaded.triggerRequest())
        || EmptyChecks.isNull(jobInstance)
        || EmptyChecks.isNull(jobInstance.getId());
    if (parentLaunchContextIncomplete) {
      return false;
    }
    TriggerRequestEntity triggerRequest = loaded.triggerRequest();
    return EmptyChecks.isNotNull(triggerRequest.getParentWorkflowRunId())
        && EmptyChecks.isNotNull(triggerRequest.getParentNodeCode());
  }

  private void inNewTransaction(Runnable action) {
    newTransaction(TransactionDefinition.PROPAGATION_REQUIRES_NEW)
        .executeWithoutResult(status -> action.run());
  }

  private TransactionTemplate newTransaction(int propagationBehavior) {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(propagationBehavior);
    return transaction;
  }

  private JobInstanceEntity buildJobInstanceEntity(
      LaunchRequest request,
      LaunchLoadResult loaded,
      Map<String, Object> effectiveParams,
      String traceId,
      Instant batchDaySlaDeadlineAt,
      ReplayExecutionGuard replayExecutionGuard) {
    String dedupKey = loaded.triggerRequest().getDedupKey();
    Long explicitParent = launchParamResolver.resolveParentInstanceId(effectiveParams);
    Long parentInstanceId = explicitParent != null
        ? explicitParent
        : (loaded.existingInstance() == null ? null : loaded.existingInstance().getId());
    Integer priority =
        loaded.jobDefinition().priority() == null ? 5 : loaded.jobDefinition().priority();
    String highWaterMarkIn = resolveHighWaterMarkIn(request.tenantId(), loaded);
    Integer runAttempt = nextRunAttempt(request, dedupKey);
    JobInstanceEntity entity = new JobInstanceEntity();
    entity.setTenantId(request.tenantId());
    entity.setJobDefinitionId(loaded.jobDefinition().id());
    entity.setTriggerRequestId(loaded.triggerRequest().getId());
    entity.setJobCode(request.jobCode());
    String instanceNo = IdGenerator.newBusinessNo("inst");
    entity.setInstanceNo(instanceNo);
    entity.setBizDate(request.bizDate());
    entity.setTriggerType(request.triggerType().code());
    entity.setInstanceStatus(JobInstanceStatus.CREATED.code());
    String batchNo = launchParamResolver.resolveBatchNo(request.bizDate(), effectiveParams);
    entity.setBatchNo(batchNo);
    String operatorId = LaunchParamResolver.resolveOperatorId(effectiveParams);
    entity.setOperatorId(operatorId);
    boolean rerunFlag =
        launchParamResolver.resolveRerunFlag(request.triggerType(), effectiveParams);
    entity.setRerunFlag(rerunFlag);
    boolean retryFlag = launchParamResolver.resolveRetryFlag(effectiveParams);
    entity.setRetryFlag(retryFlag);
    String rerunReason = launchParamResolver.resolveRerunReason(effectiveParams);
    entity.setRerunReason(rerunReason);
    Long relatedFileId = launchParamResolver.resolveRelatedFileId(effectiveParams);
    entity.setRelatedFileId(relatedFileId);
    entity.setParentInstanceId(parentInstanceId);
    entity.setQueueCode(loaded.jobDefinition().queueCode());
    entity.setWorkerGroup(loaded.jobDefinition().workerGroup());
    entity.setPriority(priority);
    entity.setDedupKey(dedupKey);
    entity.setRunAttempt(runAttempt);
    entity.setJobDefinitionVersion(loaded.jobDefinition().version());
    String rerunPolicySnapshot =
        buildRerunPolicySnapshot(request, effectiveParams, parentInstanceId, runAttempt);
    entity.setRerunPolicySnapshot(rerunPolicySnapshot);
    entity.setVersion(0L);
    entity.setExpectedPartitionCount(0);
    entity.setSuccessPartitionCount(0);
    entity.setFailedPartitionCount(0);
    entity.setTraceId(traceId);
    String paramsSnapshot = launchParamResolver.buildParamsSnapshot(
        loaded.jobDefinition(), request, effectiveParams, traceId, replayExecutionGuard);
    entity.setParamsSnapshot(paramsSnapshot);
    // V93 P0-4: 创建时从 jobDefinition 抓 calendarCode 快照, 之后 config 变更不污染历史 instance
    entity.setCalendarCode(loaded.effectiveCalendarCode());
    // V94: data_interval 直接透传 LaunchRequest 的字段; trigger 侧已计算好, orchestrator 不再算
    entity.setDataIntervalStart(request.dataIntervalStart());
    entity.setDataIntervalEnd(request.dataIntervalEnd());
    Instant deadlineEvaluationTime = BatchDateTimeSupport.utcNow();
    Instant deadlineAt = launchParamResolver.resolveDeadlineAt(
        deadlineEvaluationTime,
        request.bizDate(),
        loaded.jobDefinition(),
        effectiveParams,
        batchDaySlaDeadlineAt);
    entity.setDeadlineAt(deadlineAt);
    Integer expectedDurationSeconds =
        launchParamResolver.resolveExpectedDurationSeconds(loaded.jobDefinition(), effectiveParams);
    entity.setExpectedDurationSeconds(expectedDurationSeconds);
    entity.setHighWaterMarkIn(highWaterMarkIn);
    entity.setReplaySessionId(request.replaySessionId());
    entity.setDryRun(request.dryRun());
    return entity;
  }

  private ReplayExecutionGuard resolveReplayExecutionGuard(
      LaunchRequest request, LaunchLoadResult loaded) {
    if (EmptyChecks.isNull(loaded.triggerRequest())
        || EmptyChecks.isNull(loaded.triggerRequest().getParentWorkflowRunId())) {
      return null;
    }
    WorkflowRunEntity parentRun = workflowMappers.workflowRunMapper.selectById(
        request.tenantId(), loaded.triggerRequest().getParentWorkflowRunId());
    if (EmptyChecks.isNull(parentRun)) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.workflow.execution_snapshot_invalid");
    }
    WorkflowRunExecutionSnapshot snapshot = WorkflowRunExecutionSnapshot.from(parentRun);
    if (EmptyChecks.isNull(snapshot) || EmptyChecks.isNull(snapshot.replayOperationId())) {
      return null;
    }
    ReplayExecutionGuard replayExecutionGuard =
        snapshot.replayExecutionGuard(loaded.triggerRequest().getParentNodeCode());
    if (EmptyChecks.isNull(replayExecutionGuard)
        || !Objects.equals(
            replayExecutionGuard.replayOperationId(), snapshot.replayOperationId())) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.workflow.execution_snapshot_invalid");
    }
    return replayExecutionGuard;
  }

  private String buildRerunPolicySnapshot(
      LaunchRequest request,
      Map<String, Object> effectiveParams,
      Long parentInstanceId,
      Integer runAttempt) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put(
        "triggerType",
        request.triggerType() == null ? null : request.triggerType().code());
    snapshot.put("runAttempt", runAttempt);
    snapshot.put("parentInstanceId", parentInstanceId);
    boolean rerunFlag =
        launchParamResolver.resolveRerunFlag(request.triggerType(), effectiveParams);
    snapshot.put("rerunFlag", rerunFlag);
    String rerunReason = launchParamResolver.resolveRerunReason(effectiveParams);
    snapshot.put("rerunReason", rerunReason);
    boolean retryFlag = launchParamResolver.resolveRetryFlag(effectiveParams);
    snapshot.put("retryFlag", retryFlag);
    // §5.5 — 用户显式策略 > 平台默认。effectiveParams 由 DefaultCompensationService 注入。
    snapshot.put("resultIsolation", "NEW_JOB_INSTANCE_PER_RUN_ATTEMPT");
    String configuredResultPolicy = stringValue(effectiveParams.get("_rerunResultPolicy"));
    String resultPolicy =
        firstNonBlank(configuredResultPolicy, ResultVersionPolicy.CREATE_NEW_VERSION.code());
    snapshot.put("resultPolicy", resultPolicy);
    String configuredVersionPolicy = stringValue(effectiveParams.get("_rerunConfigVersionPolicy"));
    String versionPolicy =
        firstNonBlank(configuredVersionPolicy, "SNAPSHOT_JOB_DEFINITION_VERSION_ON_CREATE");
    snapshot.put("configVersionPolicy", versionPolicy);
    Object specifiedVersion = effectiveParams.get("_rerunConfigVersion");
    if (specifiedVersion != null) {
      snapshot.put("configVersion", specifiedVersion);
    }
    return JsonUtils.toJson(snapshot);
  }

  private static String stringValue(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private static String firstNonBlank(String preferred, String fallback) {
    return (preferred != null && !preferred.isBlank()) ? preferred : fallback;
  }

  /**
   * INCREMENTAL 模式下,从同一 (tenant, jobDefinition) 上一次成功实例的 {@code high_water_mark_out} 读出,作为本次
   * IN。FULL/CDC/未配置时一律返回 null。 首次跑没有历史成功实例时也返回 null,worker 解释为"从头开始扫"。
   */
  private String resolveHighWaterMarkIn(String tenantId, LaunchLoadResult loaded) {
    String mode = loaded.jobDefinition().executionMode();
    if (!"INCREMENTAL".equals(mode)) {
      return null;
    }
    Long jobDefinitionId = loaded.jobDefinition().id();
    if (jobDefinitionId == null) {
      return null;
    }
    JobInstanceEntity prev =
        jobMappers.jobInstanceMapper.selectLastSuccessByJobDefinition(tenantId, jobDefinitionId);
    return prev == null ? null : prev.getHighWaterMarkOut();
  }

  /**
   * 计算下一个 run_attempt：
   *
   * <ul>
   *   <li><b>RERUN</b>：同一 {@code dedup_key} 下 MAX+1（并发两条拿到同一 MAX 时靠 uk 打回一条）。
   *   <li><b>非 RERUN</b>：固定 {@code 1}。幂等业务键下只允许一条「首次触发」实例；若对 {@code max+1}， 会在「胜者已提交 attempt=1 +
   *       败者事务仍看不到实例」窗口写出 attempt=2，破坏并发幂等（Dedup E2E）。
   * </ul>
   */
  private Integer nextRunAttempt(LaunchRequest request, String dedupKey) {
    if (request.triggerType() == TriggerType.RERUN) {
      Integer max =
          jobMappers.jobInstanceMapper.selectMaxRunAttemptByDedupKey(request.tenantId(), dedupKey);
      return max == null ? 1 : max + 1;
    }
    return 1;
  }

  /** 异常链(含 cause)任一 message 含 needle 即真——用于按约束名识别具体唯一冲突。 */
  private static boolean chainContains(Throwable throwable, String needle) {
    for (Throwable t = throwable; t != null; t = t.getCause()) {
      String m = t.getMessage();
      if (m != null && m.contains(needle)) {
        return true;
      }
    }
    return false;
  }

  private LaunchResponse resolveConcurrentDuplicate(
      LaunchRequest request, LaunchLoadResult loaded, RuntimeException exception) {
    JobInstanceEntity existingInstance = jobMappers.jobInstanceMapper.selectByTenantAndDedupKey(
        request.tenantId(), loaded.triggerRequest().getDedupKey());
    if (existingInstance == null) {
      // 不是 dedup_key 命中(本 requestId 的实例已随 prepareJobInstance 事务回滚)。若违的是
      // uk_workflow_run_active(V124 部分唯一索引:tenant+workflow_definition+biz_date 在
      // CREATED/RUNNING 下唯一),说明同 workflow 同 biz_date 已有活跃 run——这是合法的"已在运行"拒绝,
      // 不是可重试故障。抛 CONFLICT 让 TriggerLaunchConsumer 优雅 ack(WARN 业务拒收),
      // 而非裸 DuplicateKeyException 一路抛到 Kafka 通用重试 → 重投到超限刷 ERROR。
      if (chainContains(exception, "uk_workflow_run_active")) {
        throw BizException.of(ResultCode.CONFLICT, "error.workflow.already_active");
      }
      throw exception;
    }
    // RERUN 并发：两个线程拿到同样的 max 并尝试 insert run_attempt=max+1，唯一键 (tenant, dedup, run_attempt)
    // 会打回其中一条。此处不能把 RERUN 当"重复请求"返回旧实例——那会掩盖业务意图。直接抛出，由调用方重试。
    if (request.triggerType() == TriggerType.RERUN) {
      throw exception;
    }
    if (isOriginalRequestReplay(loaded, existingInstance)) {
      return new LaunchResponse(existingInstance.getInstanceNo(), existingInstance.getTraceId());
    }
    int updated = jobMappers.triggerRequestMapper.updateAcceptance(
        request.tenantId(),
        request.requestId(),
        TriggerRequestStatus.DUPLICATE.code(),
        existingInstance.getId());
    if (updated == 0) {
      log.warn(
          "updateAcceptance(DUPLICATE) affected 0 rows; the row is already terminal: tenantId={} requestId={}",
          LogSanitizer.value(request.tenantId()),
          LogSanitizer.value(request.requestId()));
    }
    return new LaunchResponse(existingInstance.getInstanceNo(), existingInstance.getTraceId());
  }

  /** 判断命中的实例是否由当前 trigger_request 创建，而不是另一条同 dedup_key 的业务请求。 */
  private boolean isOriginalRequestReplay(LaunchLoadResult loaded) {
    return isOriginalRequestReplay(loaded, loaded.existingInstance());
  }

  private boolean isOriginalRequestReplay(
      LaunchLoadResult loaded, JobInstanceEntity existingInstance) {
    return loaded.triggerRequest().getId() != null
        && loaded.triggerRequest().getId().equals(existingInstance.getTriggerRequestId());
  }

  record PreparedLaunch(
      JobInstanceEntity jobInstance,
      WorkflowRunEntity workflowRun,
      List<WorkflowDagService.DagNodeResolution> initialNodes,
      Instant startedAt,
      Map<String, Object> effectiveParams) {}
}
