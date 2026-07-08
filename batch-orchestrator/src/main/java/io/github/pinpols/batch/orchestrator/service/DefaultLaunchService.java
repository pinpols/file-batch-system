package io.github.pinpols.batch.orchestrator.service;

import io.github.pinpols.batch.common.constants.BatchStatusConstants;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.enums.FailureClass;
import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.enums.JobType;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.enums.WorkflowNodeCode;
import io.github.pinpols.batch.common.enums.WorkflowNodeRunStatus;
import io.github.pinpols.batch.common.enums.WorkflowNodeType;
import io.github.pinpols.batch.common.enums.WorkflowRunStatus;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.logging.AuditLogConstants;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.IdGenerator;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.application.service.task.OrchestratorJobMappers;
import io.github.pinpols.batch.orchestrator.application.service.task.PartitionDispatchService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.OrchestratorWorkflowMappers;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowDagService;
import io.github.pinpols.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateInstanceProgressParam;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateWorkflowRunStatusParam;
import io.github.pinpols.batch.orchestrator.mapper.JobExecutionLogMapper;
import io.github.pinpols.batch.orchestrator.service.LaunchValidationService.LaunchLoadResult;
import io.micrometer.observation.annotation.Observed;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
 * <p>注意：{@link #prepareJobInstance} 必须通过 self-proxy 调用，才能让 Spring AOP 的 {@code @Transactional} 生效。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultLaunchService implements LaunchService {

  private final LaunchValidationService launchValidationService;
  private final PartitionDispatchService partitionDispatchService;
  private final OrchestratorJobMappers jobMappers;
  private final OrchestratorWorkflowMappers workflowMappers;
  private final WorkflowDagService workflowDagService;
  private final LaunchBatchDayService launchBatchDayService;
  private final BatchDayGateService batchDayGateService;
  private final LaunchParamResolver launchParamResolver;
  private final JobExecutionLogMapper jobExecutionLogMapper;
  private final ObjectProvider<DefaultLaunchService> selfProvider;

  @Override
  @Observed(name = "orch.launch", contextualName = "orch.launch")
  public LaunchResponse launch(LaunchRequest request) {
    LaunchLoadResult loaded = launchValidationService.load(request);
    LaunchResponse duplicateShortCircuit = maybeShortCircuitDuplicate(request, loaded);
    if (duplicateShortCircuit != null) {
      return duplicateShortCircuit;
    }

    String traceId = resolveTraceId(request);
    LaunchRequest routedRequest = launchBatchDayService.routeLateArrivalIfNeeded(request, loaded);
    Map<String, Object> effectiveParams =
        launchParamResolver.mergeLaunchParams(loaded.jobDefinition(), routedRequest);
    BatchDayGateService.GateDecision gateDecision =
        batchDayGateService.evaluateAndApply(routedRequest, loaded, effectiveParams, traceId);
    if (!gateDecision.allowed()) {
      return LaunchResponse.skipped(traceId);
    }

    // T1：先把 instance/workflow 写入数据库并提交，避免 T2 执行期间持有更长时间锁。
    PreparedLaunch prepared;
    try {
      prepared =
          selfProvider
              .getObject()
              .prepareJobInstance(routedRequest, loaded, effectiveParams, traceId);
    } catch (DataIntegrityViolationException exception) {
      SwallowedExceptionLogger.info(
          DefaultLaunchService.class, "catch:DataIntegrityViolationException", exception);

      // DuplicateKeyException extends DataIntegrityViolationException, covered here.
      return resolveConcurrentDuplicate(request, loaded, exception);
    } catch (RuntimeException exception) {
      // PG 唯一约束等可能被包装为 TransactionSystemException / UncategorizedDataAccess 等，需沿 cause 识别
      // 23505
      if (hasSqlStateInChain(exception, "23505")) {
        return resolveConcurrentDuplicate(request, loaded, exception);
      }
      throw exception;
    }

    dispatchAndMarkLaunched(request, effectiveParams, traceId, prepared);
    return new LaunchResponse(prepared.jobInstance().getInstanceNo(), traceId);
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
    int updated =
        jobMappers.triggerRequestMapper.updateAcceptance(
            request.tenantId(),
            request.requestId(),
            BatchStatusConstants.DUPLICATE,
            loaded.existingInstance().getId());
    if (updated == 0) {
      log.warn(
          "updateAcceptance(DUPLICATE) 0 行受影响,行已是终态: tenantId={} requestId={}",
          request.tenantId(),
          request.requestId());
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
      PreparedLaunch prepared) {
    try {
      partitionDispatchService.dispatch(
          PartitionDispatchService.DispatchContext.of(
              new PartitionDispatchService.DispatchRequest(request, effectiveParams, traceId),
              new PartitionDispatchService.DispatchRuntime(
                  prepared.jobInstance(),
                  prepared.workflowRun(),
                  prepared.initialNodes(),
                  prepared.startedAt())));
    } catch (RuntimeException ex) {
      // A1-B fix(2026-05-29):T1 已 commit workflow_run(CREATED + current_node_code=NODE_A,NODE_B
      // 之类的 fan-out 展开)。T2 dispatch 抛 BUSINESS_ERROR(典型 dispatch_business_error 资源调度
      // failFast)时,workflow_run 留半态:CREATED + current_node_code 指向"假装在跑"的下游。
      // 此处反向 finalize 为 FAILED,避免运维误判。
      finalizeWorkflowRunOnDispatchFailure(prepared.workflowRun());
      finalizeJobInstanceOnDispatchBusinessError(request, prepared.jobInstance(), ex);
      throw ex;
    }

    int updated =
        jobMappers.triggerRequestMapper.updateAcceptance(
            request.tenantId(),
            request.requestId(),
            BatchStatusConstants.LAUNCHED,
            prepared.jobInstance().getId());
    if (updated == 0) {
      log.warn(
          "updateAcceptance(LAUNCHED) 0 行受影响,行已是终态: tenantId={} requestId={}",
          request.tenantId(),
          request.requestId());
    }
  }

  private void finalizeJobInstanceOnDispatchBusinessError(
      LaunchRequest request, JobInstanceEntity jobInstance, RuntimeException exception) {
    BizException dispatchFailure = findPartitionDispatchBusinessError(exception);
    if (dispatchFailure == null || jobInstance == null || jobInstance.getId() == null) {
      return;
    }
    try {
      selfProvider
          .getObject()
          .markJobInstanceFailedDueToDispatch(request, jobInstance, dispatchFailure);
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
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markJobInstanceFailedDueToDispatch(
      LaunchRequest request, JobInstanceEntity jobInstance, BizException dispatchFailure) {
    Long expectedVersion = jobInstance.getVersion() == null ? 0L : jobInstance.getVersion();
    Instant now = BatchDateTimeSupport.utcNow();
    int rows =
        jobMappers.jobInstanceMapper.updateProgress(
            UpdateInstanceProgressParam.builder()
                .tenantId(jobInstance.getTenantId())
                .id(jobInstance.getId())
                .instanceStatus(JobInstanceStatus.FAILED.code())
                .successPartitionCount(0)
                .failedPartitionCount(0)
                .resultSummary(buildDispatchRejectSummary(dispatchFailure))
                .finishedAt(now)
                .expectedVersion(expectedVersion)
                .failureClass(FailureClass.BUSINESS_RULE.code())
                .build());
    if (rows > 0) {
      int updated =
          jobMappers.triggerRequestMapper.updateAcceptance(
              request.tenantId(),
              request.requestId(),
              BatchStatusConstants.REJECTED,
              jobInstance.getId());
      if (updated == 0) {
        log.warn(
            "updateAcceptance(REJECTED) 0 行受影响,行已是终态: tenantId={} requestId={}",
            request.tenantId(),
            request.requestId());
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
    logEntity.setExtraJson(buildDispatchRejectSummary(dispatchFailure));
    jobExecutionLogMapper.insert(logEntity);
  }

  /**
   * A1-B fix:dispatch 拒收后把 workflow_run CREATED → FAILED,清掉 current_node_code 残留。 走 self-invocation
   * 让 @Transactional 生效,独立事务不被父事务回滚波及。
   */
  private void finalizeWorkflowRunOnDispatchFailure(WorkflowRunEntity workflowRun) {
    if (workflowRun == null || workflowRun.getId() == null) {
      return;
    }
    try {
      selfProvider.getObject().markWorkflowRunFailedDueToDispatch(workflowRun);
    } catch (RuntimeException reverseEx) {
      // 反向 finalize 失败,静默捕获并抑制(不掩盖原始 dispatch 异常 cause;reverseEx 仅 oncall 视角看一眼)
      SwallowedExceptionLogger.info(
          DefaultLaunchService.class, "catch:finalizeWorkflowRunOnDispatchFailure", reverseEx);
    }
  }

  /** A1-B fix:独立事务把 workflow_run CREATED → FAILED(CAS 守护防覆盖正常 outcome 回报)。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markWorkflowRunFailedDueToDispatch(WorkflowRunEntity workflowRun) {
    workflowMappers.workflowRunMapper.updateStatus(
        UpdateWorkflowRunStatusParam.builder()
            .tenantId(workflowRun.getTenantId())
            .id(workflowRun.getId())
            .runStatus(WorkflowRunStatus.FAILED.code())
            .currentNodeCode(WorkflowNodeCode.END.code())
            .finishedAt(BatchDateTimeSupport.utcNow())
            .expectedStatuses(List.of(WorkflowRunStatus.CREATED.code()))
            .build());
  }

  /**
   * T1 事务：创建 {@code job_instance}/{@code workflow_run}，并补齐 START 节点运行态。
   *
   * <p>该事务只做"准备态写入数据库"，不触碰高竞争的 task/partition/outbox 表，从而缩短锁持有时间。
   */
  @Transactional
  public PreparedLaunch prepareJobInstance(
      LaunchRequest request,
      LaunchLoadResult loaded,
      Map<String, Object> effectiveParams,
      String traceId) {
    Instant batchDaySlaDeadlineAt =
        launchBatchDayService.resolveBatchDaySlaDeadlineAt(
            request.tenantId(), loaded.jobDefinition().calendarCode(), request.bizDate());
    JobInstanceEntity jobInstance =
        buildJobInstanceEntity(request, loaded, effectiveParams, traceId, batchDaySlaDeadlineAt);
    int inserted = jobMappers.jobInstanceMapper.insert(jobInstance);
    if (inserted <= 0) {
      throw new DuplicateKeyException(
          "duplicate job_instance idempotency key: tenant="
              + request.tenantId()
              + ", dedupKey="
              + jobInstance.getDedupKey()
              + ", runAttempt="
              + jobInstance.getRunAttempt());
    }
    launchBatchDayService.upsertBatchDayInstance(
        request, loaded.jobDefinition(), effectiveParams, batchDaySlaDeadlineAt);

    Instant startedAt = BatchDateTimeSupport.utcNow();
    if (JobType.WORKFLOW.code().equals(loaded.jobDefinition().jobType())) {
      return prepareWorkflowRunAndNodes(
          request, loaded, effectiveParams, traceId, jobInstance, startedAt);
    }
    return new PreparedLaunch(jobInstance, null, List.of(), startedAt);
  }

  private JobInstanceEntity buildJobInstanceEntity(
      LaunchRequest request,
      LaunchLoadResult loaded,
      Map<String, Object> effectiveParams,
      String traceId,
      Instant batchDaySlaDeadlineAt) {
    String dedupKey = loaded.triggerRequest().getDedupKey();
    Long explicitParent = launchParamResolver.resolveParentInstanceId(effectiveParams);
    Long parentInstanceId =
        explicitParent != null
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
    entity.setInstanceNo(IdGenerator.newBusinessNo("inst"));
    entity.setBizDate(request.bizDate());
    entity.setTriggerType(request.triggerType().code());
    entity.setInstanceStatus(JobInstanceStatus.CREATED.code());
    entity.setBatchNo(launchParamResolver.resolveBatchNo(request.bizDate(), effectiveParams));
    entity.setOperatorId(LaunchParamResolver.resolveOperatorId(effectiveParams));
    entity.setRerunFlag(
        launchParamResolver.resolveRerunFlag(request.triggerType(), effectiveParams));
    entity.setRetryFlag(launchParamResolver.resolveRetryFlag(effectiveParams));
    entity.setRerunReason(launchParamResolver.resolveRerunReason(effectiveParams));
    entity.setRelatedFileId(launchParamResolver.resolveRelatedFileId(effectiveParams));
    entity.setParentInstanceId(parentInstanceId);
    entity.setQueueCode(loaded.jobDefinition().queueCode());
    entity.setWorkerGroup(loaded.jobDefinition().workerGroup());
    entity.setPriority(priority);
    entity.setDedupKey(dedupKey);
    entity.setRunAttempt(runAttempt);
    entity.setJobDefinitionVersion(loaded.jobDefinition().version());
    entity.setRerunPolicySnapshot(
        buildRerunPolicySnapshot(request, effectiveParams, parentInstanceId, runAttempt));
    entity.setVersion(0L);
    entity.setExpectedPartitionCount(0);
    entity.setSuccessPartitionCount(0);
    entity.setFailedPartitionCount(0);
    entity.setTraceId(traceId);
    entity.setParamsSnapshot(
        launchParamResolver.buildParamsSnapshot(
            loaded.jobDefinition(), request, effectiveParams, traceId));
    // V93 P0-4: 创建时从 jobDefinition 抓 calendarCode 快照, 之后 config 变更不污染历史 instance
    entity.setCalendarCode(loaded.jobDefinition().calendarCode());
    // V94: data_interval 直接透传 LaunchRequest 的字段; trigger 侧已计算好, orchestrator 不再算
    entity.setDataIntervalStart(request.dataIntervalStart());
    entity.setDataIntervalEnd(request.dataIntervalEnd());
    entity.setDeadlineAt(
        launchParamResolver.resolveDeadlineAt(
            BatchDateTimeSupport.utcNow(),
            request.bizDate(),
            loaded.jobDefinition(),
            effectiveParams,
            batchDaySlaDeadlineAt));
    entity.setExpectedDurationSeconds(
        launchParamResolver.resolveExpectedDurationSeconds(
            loaded.jobDefinition(), effectiveParams));
    entity.setHighWaterMarkIn(highWaterMarkIn);
    entity.setReplaySessionId(request.replaySessionId());
    entity.setDryRun(request.dryRun());
    return entity;
  }

  private String buildRerunPolicySnapshot(
      LaunchRequest request,
      Map<String, Object> effectiveParams,
      Long parentInstanceId,
      Integer runAttempt) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put(
        "triggerType", request.triggerType() == null ? null : request.triggerType().code());
    snapshot.put("runAttempt", runAttempt);
    snapshot.put("parentInstanceId", parentInstanceId);
    snapshot.put(
        "rerunFlag", launchParamResolver.resolveRerunFlag(request.triggerType(), effectiveParams));
    snapshot.put("rerunReason", launchParamResolver.resolveRerunReason(effectiveParams));
    snapshot.put("retryFlag", launchParamResolver.resolveRetryFlag(effectiveParams));
    // §5.5 — 用户显式策略 > 平台默认。effectiveParams 由 DefaultCompensationService 注入。
    snapshot.put("resultIsolation", "NEW_JOB_INSTANCE_PER_RUN_ATTEMPT");
    snapshot.put(
        "resultPolicy",
        firstNonBlank(
            stringValue(effectiveParams.get("_rerunResultPolicy")), "CREATE_NEW_VERSION"));
    snapshot.put(
        "configVersionPolicy",
        firstNonBlank(
            stringValue(effectiveParams.get("_rerunConfigVersionPolicy")),
            "SNAPSHOT_JOB_DEFINITION_VERSION_ON_CREATE"));
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

  private PreparedLaunch prepareWorkflowRunAndNodes(
      LaunchRequest request,
      LaunchLoadResult loaded,
      Map<String, Object> effectiveParams,
      String traceId,
      JobInstanceEntity jobInstance,
      Instant startedAt) {
    List<WorkflowDagService.DagNodeResolution> initialNodes =
        workflowDagService.resolveInitialNodes(
            loaded.workflowDefinition().id(),
            launchParamResolver.buildPayloadJson(effectiveParams));

    WorkflowRunEntity workflowRun = new WorkflowRunEntity();
    workflowRun.setTenantId(request.tenantId());
    workflowRun.setWorkflowDefinitionId(loaded.workflowDefinition().id());
    workflowRun.setRelatedJobInstanceId(jobInstance.getId());
    workflowRun.setBizDate(request.bizDate());
    workflowRun.setRunStatus(WorkflowRunStatus.CREATED.code());
    workflowRun.setCurrentNodeCode(resolveInitialCurrentNode(initialNodes));
    workflowRun.setTraceId(traceId);
    workflowRun.setDryRun(request.dryRun());
    workflowMappers.workflowRunMapper.insert(workflowRun);

    WorkflowNodeRunEntity startNodeRun = new WorkflowNodeRunEntity();
    startNodeRun.setWorkflowRunId(workflowRun.getId());
    startNodeRun.setNodeCode(WorkflowNodeCode.START.code());
    startNodeRun.setNodeType(WorkflowNodeType.START.code());
    startNodeRun.setRunSeq(1);
    startNodeRun.setNodeStatus(WorkflowNodeRunStatus.SUCCESS.code());
    startNodeRun.setRetryCount(0);
    startNodeRun.setDurationMs(0L);
    startNodeRun.setStartedAt(startedAt);
    startNodeRun.setFinishedAt(startedAt);
    workflowMappers.workflowNodeRunMapper.insert(startNodeRun);

    return new PreparedLaunch(jobInstance, workflowRun, initialNodes, startedAt);
  }

  private String resolveInitialCurrentNode(
      List<WorkflowDagService.DagNodeResolution> initialNodes) {
    if (initialNodes == null || initialNodes.isEmpty()) {
      return WorkflowNodeCode.START.code();
    }
    Set<String> activeNodes = new LinkedHashSet<>();
    for (WorkflowDagService.DagNodeResolution node : initialNodes) {
      if (node == null || WorkflowNodeCode.START.code().equals(node.nodeCode())) {
        continue;
      }
      activeNodes.add(node.nodeCode());
    }
    return activeNodes.isEmpty() ? WorkflowNodeCode.START.code() : String.join(",", activeNodes);
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

  private static boolean hasSqlStateInChain(Throwable throwable, String sqlState) {
    for (Throwable t = throwable; t != null; t = t.getCause()) {
      if (t instanceof SQLException sql) {
        if (sqlState.equals(sql.getSQLState())) {
          return true;
        }
        String m = sql.getMessage();
        if (m != null && m.contains("uk_job_instance_tenant_dedup")) {
          return true;
        }
      }
    }
    return false;
  }

  private LaunchResponse resolveConcurrentDuplicate(
      LaunchRequest request, LaunchLoadResult loaded, RuntimeException exception) {
    JobInstanceEntity existingInstance =
        jobMappers.jobInstanceMapper.selectByTenantAndDedupKey(
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
    int updated =
        jobMappers.triggerRequestMapper.updateAcceptance(
            request.tenantId(),
            request.requestId(),
            BatchStatusConstants.DUPLICATE,
            existingInstance.getId());
    if (updated == 0) {
      log.warn(
          "updateAcceptance(DUPLICATE) 0 行受影响,行已是终态: tenantId={} requestId={}",
          request.tenantId(),
          request.requestId());
    }
    return new LaunchResponse(existingInstance.getInstanceNo(), existingInstance.getTraceId());
  }

  record PreparedLaunch(
      JobInstanceEntity jobInstance,
      WorkflowRunEntity workflowRun,
      List<WorkflowDagService.DagNodeResolution> initialNodes,
      Instant startedAt) {}
}
