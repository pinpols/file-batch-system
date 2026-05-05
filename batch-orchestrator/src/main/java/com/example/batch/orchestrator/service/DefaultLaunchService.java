package com.example.batch.orchestrator.service;

import com.example.batch.common.constants.BatchStatusConstants;
import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.enums.JobInstanceStatus;
import com.example.batch.common.enums.JobType;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.enums.WorkflowNodeCode;
import com.example.batch.common.enums.WorkflowNodeRunStatus;
import com.example.batch.common.enums.WorkflowNodeType;
import com.example.batch.common.enums.WorkflowRunStatus;
import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.persistence.entity.WorkflowRunEntity;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.common.utils.IdGenerator;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.application.service.task.OrchestratorJobMappers;
import com.example.batch.orchestrator.application.service.task.PartitionDispatchService;
import com.example.batch.orchestrator.application.service.workflow.OrchestratorWorkflowMappers;
import com.example.batch.orchestrator.application.service.workflow.WorkflowDagService;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import com.example.batch.orchestrator.service.LaunchValidationService.LaunchLoadResult;
import io.micrometer.observation.annotation.Observed;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 批任务启动（Launch）的核心入口：把一次触发请求落地为可调度的运行态，并驱动后续分片/任务派发。
 *
 * <p>这里把 launch 拆成两段<strong>独立提交</strong>的事务（T1/T2），目的是降低锁竞争与提升可重试性：
 *
 * <ul>
 *   <li><strong>T1（准备态落库）</strong>：只创建 {@code job_instance}/{@code workflow_run} 以及 START 节点的运行态，
 *       快速提交，作为后续调度/派发的"事实源"。
 *   <li><strong>T2（运行态构建与派发）</strong>：创建 partition/task、写 outbox，并推进 instance/workflow 状态。 高竞争表只在
 *       T2 短事务里触碰，避免长事务持锁。
 * </ul>
 *
 * <p>注意：{@link #prepareJobInstance} 必须通过 self-proxy 调用，才能让 Spring AOP 的 {@code @Transactional} 生效。
 */
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
        launchParamResolver.mergeLaunchParams(
            loaded.jobDefinition(), routedRequest.triggerType(), routedRequest.params());
    BatchDayGateService.GateDecision gateDecision =
        batchDayGateService.evaluateAndApply(routedRequest, loaded, effectiveParams, traceId);
    if (!gateDecision.allowed()) {
      return LaunchResponse.skipped(traceId);
    }

    // T1：先把 instance/workflow 落库并提交，避免 T2 执行期间持有更长时间锁。
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
    jobMappers.triggerRequestMapper.updateAcceptance(
        request.tenantId(),
        request.requestId(),
        BatchStatusConstants.DUPLICATE,
        loaded.existingInstance().getId());
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
    partitionDispatchService.dispatch(
        PartitionDispatchService.DispatchContext.of(
            new PartitionDispatchService.DispatchRequest(request, effectiveParams, traceId),
            new PartitionDispatchService.DispatchRuntime(
                prepared.jobInstance(),
                prepared.workflowRun(),
                prepared.initialNodes(),
                prepared.startedAt())));

    jobMappers.triggerRequestMapper.updateAcceptance(
        request.tenantId(),
        request.requestId(),
        BatchStatusConstants.LAUNCHED,
        prepared.jobInstance().getId());
  }

  /**
   * T1 事务：创建 {@code job_instance}/{@code workflow_run}，并补齐 START 节点运行态。
   *
   * <p>该事务只做"准备态落库"，不触碰高竞争的 task/partition/outbox 表，从而缩短锁持有时间。
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
    jobMappers.jobInstanceMapper.insert(jobInstance);
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
    return JobInstanceEntity.builder()
        .tenantId(request.tenantId())
        .jobDefinitionId(loaded.jobDefinition().id())
        .triggerRequestId(loaded.triggerRequest().getId())
        .jobCode(request.jobCode())
        .instanceNo(IdGenerator.newBusinessNo("inst"))
        .bizDate(request.bizDate())
        .triggerType(request.triggerType().code())
        .instanceStatus(JobInstanceStatus.CREATED.code())
        .batchNo(launchParamResolver.resolveBatchNo(request.bizDate(), effectiveParams))
        .operatorId(LaunchParamResolver.resolveOperatorId(effectiveParams))
        .rerunFlag(launchParamResolver.resolveRerunFlag(request.triggerType(), effectiveParams))
        .retryFlag(launchParamResolver.resolveRetryFlag(effectiveParams))
        .rerunReason(launchParamResolver.resolveRerunReason(effectiveParams))
        .relatedFileId(launchParamResolver.resolveRelatedFileId(effectiveParams))
        .parentInstanceId(parentInstanceId)
        .queueCode(loaded.jobDefinition().queueCode())
        .workerGroup(loaded.jobDefinition().workerGroup())
        .priority(priority)
        .dedupKey(dedupKey)
        .runAttempt(runAttempt)
        .jobDefinitionVersion(loaded.jobDefinition().version())
        .rerunPolicySnapshot(
            buildRerunPolicySnapshot(request, effectiveParams, parentInstanceId, runAttempt))
        .version(0L)
        .expectedPartitionCount(0)
        .successPartitionCount(0)
        .failedPartitionCount(0)
        .traceId(traceId)
        .paramsSnapshot(
            launchParamResolver.buildParamsSnapshot(
                loaded.jobDefinition(), request, effectiveParams, traceId))
        // V93 P0-4: 创建时从 jobDefinition 抓 calendarCode 快照, 之后 config 变更不污染历史 instance
        .calendarCode(loaded.jobDefinition().calendarCode())
        // V94: data_interval 直接透传 LaunchRequest 的字段; trigger 侧已计算好, orchestrator 不再算
        .dataIntervalStart(request.dataIntervalStart())
        .dataIntervalEnd(request.dataIntervalEnd())
        .deadlineAt(
            launchParamResolver.resolveDeadlineAt(
                BatchDateTimeSupport.utcNow(),
                request.bizDate(),
                loaded.jobDefinition(),
                effectiveParams,
                batchDaySlaDeadlineAt))
        .expectedDurationSeconds(
            launchParamResolver.resolveExpectedDurationSeconds(
                loaded.jobDefinition(), effectiveParams))
        .highWaterMarkIn(highWaterMarkIn)
        .build();
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
    snapshot.put("resultIsolation", "NEW_JOB_INSTANCE_PER_RUN_ATTEMPT");
    snapshot.put("configVersionPolicy", "SNAPSHOT_JOB_DEFINITION_VERSION_ON_CREATE");
    return JsonUtils.toJson(snapshot);
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
      throw exception;
    }
    // RERUN 并发：两个线程拿到同样的 max 并尝试 insert run_attempt=max+1，唯一键 (tenant, dedup, run_attempt)
    // 会打回其中一条。此处不能把 RERUN 当"重复请求"返回旧实例——那会掩盖业务意图。直接抛出，由调用方重试。
    if (request.triggerType() == TriggerType.RERUN) {
      throw exception;
    }
    jobMappers.triggerRequestMapper.updateAcceptance(
        request.tenantId(),
        request.requestId(),
        BatchStatusConstants.DUPLICATE,
        existingInstance.getId());
    return new LaunchResponse(existingInstance.getInstanceNo(), existingInstance.getTraceId());
  }

  record PreparedLaunch(
      JobInstanceEntity jobInstance,
      WorkflowRunEntity workflowRun,
      List<WorkflowDagService.DagNodeResolution> initialNodes,
      Instant startedAt) {}
}
