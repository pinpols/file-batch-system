package io.github.pinpols.batch.orchestrator.application.service.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.TaskStatus;
import io.github.pinpols.batch.common.enums.TriggerRequestStatus;
import io.github.pinpols.batch.common.enums.WorkflowNodeCode;
import io.github.pinpols.batch.common.enums.WorkflowNodeRunStatus;
import io.github.pinpols.batch.common.enums.WorkflowNodeType;
import io.github.pinpols.batch.common.enums.WorkflowRunStatus;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.application.engine.OutboxEventKeyGenerator;
import io.github.pinpols.batch.orchestrator.application.engine.TaskDispatchOutboxService;
import io.github.pinpols.batch.orchestrator.application.plan.SchedulePlan;
import io.github.pinpols.batch.orchestrator.application.plan.SchedulePlanBuilder;
import io.github.pinpols.batch.orchestrator.application.plan.SchedulePlanCommand;
import io.github.pinpols.batch.orchestrator.application.plan.SchedulePlanSupport;
import io.github.pinpols.batch.orchestrator.application.scheduler.DryRunSchedulingPriority;
import io.github.pinpols.batch.orchestrator.application.scheduler.ResourceScheduler;
import io.github.pinpols.batch.orchestrator.application.service.WorkflowNodeRunSupport;
import io.github.pinpols.batch.orchestrator.application.service.task.ChildJobLaunchSupport;
import io.github.pinpols.batch.orchestrator.application.service.task.OrchestratorJobMappers;
import io.github.pinpols.batch.orchestrator.application.service.task.PartitionLifecycleService;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskCreationService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.CrossDayDependencyResolver.ResolutionResult;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDependencyWaitingLaunchEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateNodeRunStatusParam;
import io.github.pinpols.batch.orchestrator.domain.query.JobPartitionQuery;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceAdmissionAction;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceSchedulingDecision;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceSchedulingRequest;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobDependencyWaitingLaunchMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowDefinitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import io.github.pinpols.batch.orchestrator.service.LaunchRequestIdentityGuard;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 工作流 DAG 节点派发：按节点类型分三条路径派发，所有路径的幂等由 {@link #isNodeAlreadyActivated} 配合 {@code workflow_node_run}
 * 行锁保证（见 {@code C-3}），防止同一节点被并发 dispatch 重复激活。
 *
 * <p>四种派发模式：
 *
 * <ul>
 *   <li><b>GATEWAY / START</b>：本身无工作负载，合成"立即完成"的 node_run（RUNNING→SUCCESS 同事务） 后按条件解析下游节点并递归
 *       dispatch，保证 DAG 流转不被无作业节点阻塞。
 *   <li><b>JOB</b>：启动独立子 Job 实例，并将其唯一关联到对应的 Workflow 节点运行。
 *   <li><b>WAIT</b>：创建 RUNNING workflow_node_run 供 SensorPollScheduler 持久化轮询，不创建 worker 任务或分区。
 *   <li><b>TASK</b>：普通节点，走 {@code SchedulePlanBuilder} 生成分片 + 资源调度决策 + 创建任务 + 落 outbox
 *       派发事件（同事务）。
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultWorkflowNodeDispatchService implements WorkflowNodeDispatchService {

  private final OrchestratorJobMappers jobMappers;
  private final OrchestratorWorkflowMappers workflowMappers;
  private final SchedulePlanBuilder schedulePlanBuilder;
  private final PartitionLifecycleService partitionLifecycleService;
  private final TaskDispatchOutboxService taskDispatchOutboxService;
  private final WorkflowDagService workflowDagService;
  private final ResourceScheduler resourceScheduler;
  private final TaskCreationService taskCreationService;
  // P2-4 god-class-decomposition: payload 拼装 + 上游产物合并 + ADR-009 DSL 解析全套抽到 collaborator
  private final WorkflowNodePayloadBuilder payloadBuilder;
  // JOB 节点子作业拉起与 Workflow 节点归属由 collaborator 负责。
  private final ChildJobLaunchSupport childJobLaunchSupport;
  // ADR-018 跨批量日依赖解析；NULL（无依赖）跳过；REQUIRED 缺失 → WAITING_DEPENDENCY；解析失败 → FAILED
  private final CrossDayDependencyResolver crossDayDependencyResolver;
  private final WorkflowNodeTerminalFinalizer workflowNodeTerminalFinalizer;
  private final SecretPayloadProtector secretPayloadProtector;
  private final JobDependencyWaitingLaunchMapper waitingLaunchMapper;
  private final BatchDayInstanceMapper batchDayInstanceMapper;
  private final WorkflowDefinitionMapper workflowDefinitionMapper;

  /**
   * 派发 DAG 单个节点。依据 {@code nodeType} 路由到 gateway / JOB / task 三条路径之一；返回新建成的分片数量， 调用方据此推进 {@code
   * job_instance.expected_partition_count}。返回 0 代表节点不具备 dispatch 条件 （依赖未就绪 / 已被其他线程激活 /
   * 节点定义缺失等），不是错误。
   */
  @Override
  @Transactional
  public int dispatchNode(
      JobInstanceEntity jobInstance,
      WorkflowRunEntity workflowRun,
      WorkflowDagService.DagNodeResolution node,
      String sourcePayload,
      String traceId) {
    if (EmptyChecks.isNull(workflowRun) || EmptyChecks.isNull(node)) {
      return 0;
    }
    if (EmptyChecks.isNotNull(workflowRun.getRelatedJobInstanceId())) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.workflow.root_job_launch_retired");
    }
    WorkflowRunExecutionSnapshot frozenSnapshot = WorkflowRunExecutionSnapshot.from(workflowRun);
    String calendarCode = EmptyChecks.isNotNull(frozenSnapshot)
        ? frozenSnapshot.workflowCalendarCode()
        : resolveCurrentCalendarCode(workflowRun);
    if (EmptyChecks.isBlank(calendarCode) || EmptyChecks.isNull(workflowRun.getBizDate())) {
      return 0;
    }
    // 与子请求准入的加锁顺序保持一致：先获取批次日围栏，再锁定父记录。
    // 使用锁定后的当前状态，不使用 Worker 报告中可能过期的快照。
    batchDayInstanceMapper.acquireSettlementFence(
        workflowRun.getTenantId(), calendarCode, workflowRun.getBizDate());
    WorkflowRunEntity currentRun = workflowMappers.workflowRunMapper.selectByTenantAndIdForUpdate(
        workflowRun.getTenantId(), workflowRun.getId());
    boolean currentRunIsMissingOrTerminal = EmptyChecks.isNull(currentRun)
        || WorkflowRunStatus.terminalCodes().contains(currentRun.getRunStatus());
    if (currentRunIsMissingOrTerminal) {
      log.info(
          "downstream node dispatch skipped for terminal WorkflowRun: workflowRunId={}, nodeCode={}, status={}",
          workflowRun.getId(),
          node.nodeCode(),
          EmptyChecks.isNull(currentRun) ? "MISSING" : currentRun.getRunStatus());
      return 0;
    }
    workflowRun = currentRun;
    // 已完成节点的报告携带该节点自己的 Job Instance。它只是执行细节，
    // 不能作为 Workflow 下一节点的虚构父作业。
    jobInstance = null;
    // ADR-044:workflow_run 暂停时不推进下游节点,resume 后再继续。
    if (WorkflowRunStatus.PAUSED.code().equals(workflowRun.getRunStatus())) {
      return 0;
    }
    // P1-4：先取 FOR UPDATE 行锁再做 readiness 检查。原顺序（先 readiness 后 lock）有 TOCTOU：
    // 两个上游同时上报 SUCCESS 触发 join 节点，两个线程可同时通过 readiness 检查、再去拿锁。
    // 调换顺序后，若存在历史 READY/RUNNING 记录，第二个线程在 FOR UPDATE 上阻塞或读取到已激活状态直接退出。
    // 残留 race（首次激活、节点 run 行尚未存在）由下游 unique 索引 / DuplicateKeyException catch 回退。
    if (isNodeAlreadyActivated(workflowRun.getId(), node.nodeCode())) {
      return 0;
    }
    if (!workflowDagService.isNodeReadyForRunDispatch(
        workflowRun, node.nodeCode(), sourcePayload)) {
      return 0;
    }
    WorkflowRunExecutionSnapshot executionSnapshot = WorkflowRunExecutionSnapshot.from(workflowRun);
    WorkflowNodeEntity workflowNode = EmptyChecks.isNull(executionSnapshot)
        ? workflowMappers.workflowNodeMapper.selectByWorkflowDefinitionIdAndNodeCode(
            workflowRun.getWorkflowDefinitionId(), node.nodeCode())
        : executionSnapshot.node(node.nodeCode());
    if (workflowNode == null) {
      return 0;
    }
    // ADR-018 §决策 §解析时机 — 跨批量日依赖解析；REQUIRED 缺失 → WAITING_DEPENDENCY；解析失败 → 节点 FAIL
    CrossDayDependencyOutcome outcome = evaluateCrossDayDependencies(
        jobInstance, workflowRun, executionSnapshot, workflowNode, node, sourcePayload);
    if (outcome.halt()) {
      return 0;
    }
    String effectivePayload = outcome.effectivePayload();
    if (WorkflowNodeType.END.code().equals(workflowNode.getNodeType())) {
      Instant finishedAt = BatchDateTimeSupport.utcNow();
      createTerminalNodeRun(workflowRun.getId(), node, finishedAt);
      workflowNodeTerminalFinalizer.finalizeIfAllNodesTerminal(workflowRun);
      return 0;
    }
    if (isGatewayNode(workflowNode.getNodeType())) {
      return dispatchGatewayNode(jobInstance, workflowRun, node, effectivePayload);
    }
    if (isJobNode(workflowNode.getNodeType())) {
      try {
        return childJobLaunchSupport.dispatchJobNode(
            workflowRun, node, workflowNode, effectivePayload, traceId);
      } catch (ChildJobLaunchSupport.RejectedChildJobLaunchException rejection) {
        return recordRejectedChildJobLaunch(
            workflowRun, node, effectivePayload, traceId, rejection);
      }
    }
    if (isWaitNode(workflowNode.getNodeType())) {
      return dispatchWaitNode(workflowRun, node, effectivePayload);
    }

    if (EmptyChecks.isNull(jobInstance)) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.workflow.legacy_task_node_requires_job_migration");
    }
    return dispatchTaskNode(
        jobInstance, workflowRun, node, workflowNode, effectivePayload, traceId);
  }

  private String resolveCurrentCalendarCode(WorkflowRunEntity workflowRun) {
    WorkflowDefinitionEntity definition =
        workflowDefinitionMapper.selectById(workflowRun.getWorkflowDefinitionId());
    if (EmptyChecks.isNull(definition)
        || !Objects.equals(definition.tenantId(), workflowRun.getTenantId())) {
      return null;
    }
    return definition.calendarCode();
  }

  @Override
  @Transactional
  public void rejectDeferredChildRequest(String tenantId, String requestId) {
    waitingLaunchMapper.setTenantContext(tenantId);
    TriggerRequestEntity original =
        jobMappers.triggerRequestMapper.selectByTenantAndRequestId(tenantId, requestId);
    boolean deferredChildRequestCannotBeRejected = EmptyChecks.isNull(original)
        || !TriggerRequestStatus.REJECTED.code().equals(original.getRequestStatus())
        || EmptyChecks.isNull(original.getParentWorkflowRunId())
        || EmptyChecks.isBlank(original.getParentNodeCode())
        || waitingLaunchMapper.hasJobInstanceForRequest(tenantId, original.getId());
    if (deferredChildRequestCannotBeRejected) {
      return;
    }
    WorkflowRunEntity parent =
        workflowMappers.workflowRunMapper.selectById(tenantId, original.getParentWorkflowRunId());
    if (EmptyChecks.isNull(parent)
        || !List.of(WorkflowRunStatus.CREATED.code(), WorkflowRunStatus.RUNNING.code())
            .contains(parent.getRunStatus())) {
      return;
    }
    JobDependencyWaitingLaunchEntity waiting =
        waitingLaunchMapper.selectByTenantAndRequestId(tenantId, requestId);
    if (EmptyChecks.isNull(waiting)
        || !JobDependencyWaitingLaunchMapper.WaitStatus.CANCELLED
            .name()
            .equals(waiting.waitStatus())) {
      return;
    }
    WorkflowNodeRunEntity node = workflowMappers.workflowNodeRunMapper.selectLatestForUpdate(
        original.getParentWorkflowRunId(), original.getParentNodeCode());
    boolean deferredChildNodeIsNotReady = EmptyChecks.isNull(node)
        || !WorkflowNodeType.JOB.code().equals(node.getNodeType())
        || !WorkflowNodeRunStatus.READY.code().equals(node.getNodeStatus())
        || EmptyChecks.isNotNull(node.getJobInstanceId());
    if (deferredChildNodeIsNotReady) {
      return;
    }
    LaunchRequest frozen = JsonUtils.fromJson(original.getLaunchPayload(), LaunchRequest.class);
    if (!LaunchRequestIdentityGuard.matches(frozen, original)
        || EmptyChecks.isNull(frozen.params())) {
      return;
    }
    Long frozenParentNodeRunId = integerIdentity(frozen.params().get("_parentNodeRunId"));
    if (!Objects.equals(frozenParentNodeRunId, node.getId())) {
      return;
    }
    Long frozenParentWorkflowRunId = integerIdentity(frozen.params().get("_parentWorkflowRunId"));
    if (!Objects.equals(frozenParentWorkflowRunId, original.getParentWorkflowRunId())) {
      return;
    }
    Object frozenParentNodeCode = frozen.params().get("_parentNodeCode");
    if (!Objects.equals(frozenParentNodeCode, original.getParentNodeCode())) {
      return;
    }
    WorkflowRunEntity run =
        workflowMappers.workflowRunMapper.selectById(tenantId, original.getParentWorkflowRunId());
    boolean parentRunNoLongerMatchesDeferredChild = EmptyChecks.isNull(run)
        || !List.of(WorkflowRunStatus.CREATED.code(), WorkflowRunStatus.RUNNING.code())
            .contains(run.getRunStatus())
        || !Objects.equals(original.getBizDate(), run.getBizDate())
        || Boolean.TRUE.equals(original.getDryRun()) != Boolean.TRUE.equals(run.getDryRun());
    if (parentRunNoLongerMatchesDeferredChild) {
      return;
    }
    WorkflowRunExecutionSnapshot snapshot = WorkflowRunExecutionSnapshot.from(run);
    WorkflowNodeEntity definition =
        EmptyChecks.isNull(snapshot) ? null : snapshot.node(node.getNodeCode());
    if (EmptyChecks.isNull(definition)
        || !Objects.equals(definition.getRelatedJobCode(), original.getJobCode())) {
      return;
    }
    String sourcePayload = EmptyChecks.isBlank(node.getSourcePayloadProtected())
        ? null
        : secretPayloadProtector.reveal(node.getSourcePayloadProtected());
    BizException rejection = BizException.of(
        ResultCode.STATE_CONFLICT,
        ResultCode.STATE_CONFLICT.detailKey(),
        "Deferred child Job request was rejected before instance creation");
    WorkflowDagService.DagNodeResolution rejectedNode =
        new WorkflowDagService.DagNodeResolution(node.getNodeCode(), node.getNodeType());
    String workflowTraceId = run.getTraceId();
    ChildJobLaunchSupport.RejectedChildJobLaunchException rejectionException =
        new ChildJobLaunchSupport.RejectedChildJobLaunchException(node.getId(), rejection);
    recordRejectedChildJobLaunch(
        run, rejectedNode, sourcePayload, workflowTraceId, rejectionException);
  }

  private static Long integerIdentity(Object value) {
    return value instanceof Long number
        ? number
        : value instanceof Integer number ? number.longValue() : null;
  }

  /** WAIT 由持久化 sensor 扫描器驱动；保持 RUNNING 才会进入到期探测查询。 */
  private int dispatchWaitNode(
      WorkflowRunEntity workflowRun,
      WorkflowDagService.DagNodeResolution node,
      String sourcePayload) {
    Instant now = BatchDateTimeSupport.utcNow();
    WorkflowNodeRunEntity runningNode = new WorkflowNodeRunEntity();
    runningNode.setWorkflowRunId(workflowRun.getId());
    runningNode.setNodeCode(node.nodeCode());
    runningNode.setNodeType(WorkflowNodeType.WAIT.code());
    int nextRunSequence = WorkflowNodeRunSupport.nextRunSeq(
        workflowMappers.workflowNodeRunMapper, workflowRun.getId(), node.nodeCode());
    runningNode.setRunSeq(nextRunSequence);
    runningNode.setNodeStatus(WorkflowNodeRunStatus.RUNNING.code());
    runningNode.setRetryCount(0);
    String protectedSourcePayload = protectSourcePayload(sourcePayload);
    runningNode.setSourcePayloadProtected(protectedSourcePayload);
    runningNode.setStartedAt(now);
    runningNode.setDurationMs(0L);
    workflowMappers.workflowNodeRunMapper.insert(runningNode);
    return 0;
  }

  /**
   * ADR-018 解析钩子。WAITING / FAILED 时写 {@link WorkflowNodeRunStatus#WAITING_DEPENDENCY} 或 {@link
   * WorkflowNodeRunStatus#FAILED} 节点 run，并 short-circuit dispatchNode 返回 0；RESOLVED 时把 resolved map
   * 注入 source payload 的 {@code crossDay} 字段（沿用 ADR-009 DSL 引用）。
   */
  private CrossDayDependencyOutcome evaluateCrossDayDependencies(
      JobInstanceEntity jobInstance,
      WorkflowRunEntity workflowRun,
      WorkflowRunExecutionSnapshot executionSnapshot,
      WorkflowNodeEntity workflowNode,
      WorkflowDagService.DagNodeResolution node,
      String sourcePayload) {
    String jsonSpec = workflowNode.getCrossDayDependencies();
    if (jsonSpec == null || jsonSpec.isBlank()) {
      return CrossDayDependencyOutcome.proceed(sourcePayload);
    }
    String tenantId =
        EmptyChecks.isNull(jobInstance) ? workflowRun.getTenantId() : jobInstance.getTenantId();
    LocalDate bizDate =
        EmptyChecks.isNull(jobInstance) ? workflowRun.getBizDate() : jobInstance.getBizDate();
    if (isJobRerun(sourcePayload)) {
      return restoreRerunCrossDaySnapshot(
          tenantId, jsonSpec, workflowNode, workflowRun, node, sourcePayload);
    }
    boolean workflowReplay = EmptyChecks.isNotNull(executionSnapshot)
        && EmptyChecks.isNotNull(executionSnapshot.replayOperationId());
    ResolutionResult result;
    if (workflowReplay) {
      CrossDayDependencyResolver pinnedResolver = crossDayDependencyResolver;
      boolean dryRun = Boolean.TRUE.equals(workflowRun.getDryRun());
      Map<String, List<Long>> pinnedResultVersionIds =
          executionSnapshot.crossDayDependencyResultVersionIds(node.nodeCode());
      result =
          pinnedResolver.resolvePinned(tenantId, bizDate, jsonSpec, dryRun, pinnedResultVersionIds);
    } else {
      result = crossDayDependencyResolver.resolve(
          tenantId, bizDate, jsonSpec, Boolean.TRUE.equals(workflowRun.getDryRun()));
    }
    if (result.isResolved()) {
      String mergedPayload = mergeCrossDayPayload(sourcePayload, result.getResolved());
      return CrossDayDependencyOutcome.proceed(mergedPayload);
    }
    if (result.isWaiting()) {
      writeWaitingDependencyNodeRun(
          workflowRun.getId(), node, result.getWaitingReasons(), sourcePayload);
      log.info(
          "workflow_node WAITING_DEPENDENCY: tenantId={}, workflowRunId={}, nodeCode={},"
              + " reasons={}",
          tenantId,
          workflowRun.getId(),
          node.nodeCode(),
          result.getWaitingReasons());
      return CrossDayDependencyOutcome.halted();
    }
    // FAILED — fail-fast，节点直接失败
    writeFailedNodeRun(workflowRun.getId(), node, result.getFailureCode());
    log.warn(
        "workflow_node cross-day dep FAILED: tenantId={}, workflowRunId={}, nodeCode={},"
            + " failureCode={}",
        tenantId,
        workflowRun.getId(),
        node.nodeCode(),
        result.getFailureCode());
    return CrossDayDependencyOutcome.halted();
  }

  /**
   * JOB_RERUN 复用其来源 JobInstance 中持久化的跨日结果载荷。若再次解析
   * EFFECTIVE/LATEST，会悄悄改变不同尝试之间的业务输入。
   */
  private CrossDayDependencyOutcome restoreRerunCrossDaySnapshot(
      String tenantId,
      String currentDependencySpec,
      WorkflowNodeEntity currentNode,
      WorkflowRunEntity rerunRun,
      WorkflowDagService.DagNodeResolution node,
      String sourcePayload) {
    try {
      Map<String, Object> requestParams = WorkflowNodePayloadBuilder.parsePayloadMap(sourcePayload);
      Long sourceInstanceId = positiveLong(requestParams.get("parentInstanceId"));
      JobInstanceEntity source = EmptyChecks.isNull(sourceInstanceId)
          ? null
          : jobMappers.jobInstanceMapper.selectById(tenantId, sourceInstanceId);
      boolean sourceJobIdentityDoesNotMatch = EmptyChecks.isNull(source)
          || !tenantId.equals(source.getTenantId())
          || !Objects.equals(currentNode.getRelatedJobCode(), source.getJobCode());
      if (sourceJobIdentityDoesNotMatch) {
        return failRerunCrossDaySnapshot(rerunRun, node, "SOURCE_INSTANCE_INVALID");
      }

      WorkflowRunEntity sourceRun =
          workflowMappers.workflowRunMapper.selectByNodeJobInstanceId(tenantId, sourceInstanceId);
      if (EmptyChecks.isNull(sourceRun)) {
        return failRerunCrossDaySnapshot(rerunRun, node, "SOURCE_RUN_MISSING");
      }
      WorkflowNodeRunEntity sourceNodeRun =
          workflowMappers.workflowNodeRunMapper.selectByWorkflowRunId(sourceRun.getId()).stream()
              .filter(row -> Objects.equals(sourceInstanceId, row.getJobInstanceId()))
              .findFirst()
              .orElse(null);
      WorkflowRunExecutionSnapshot sourceSnapshot = WorkflowRunExecutionSnapshot.from(sourceRun);
      WorkflowNodeEntity sourceNode =
          EmptyChecks.isNull(sourceNodeRun) || EmptyChecks.isNull(sourceSnapshot)
              ? null
              : sourceSnapshot.node(sourceNodeRun.getNodeCode());
      if (EmptyChecks.isNull(sourceNode)
          || !sameDependencySpec(sourceNode.getCrossDayDependencies(), currentDependencySpec)) {
        return failRerunCrossDaySnapshot(rerunRun, node, "DEPENDENCY_SPEC_CHANGED");
      }

      Map<String, Object> sourceParams =
          JsonUtils.fromJson(source.getParamsSnapshot(), new TypeReference<>() {});
      Object effectiveParamsValue =
          EmptyChecks.isNull(sourceParams) ? null : sourceParams.get("effectiveParams");
      if (!(effectiveParamsValue instanceof Map<?, ?> effectiveParams)) {
        return failRerunCrossDaySnapshot(rerunRun, node, "DEPENDENCY_SNAPSHOT_MISSING");
      }
      Map<String, Object> pinnedCrossDay = new LinkedHashMap<>();
      Object rawCrossDay = effectiveParams.get("crossDay");
      if (EmptyChecks.isNotNull(rawCrossDay) && !(rawCrossDay instanceof Map<?, ?>)) {
        return failRerunCrossDaySnapshot(rerunRun, node, "DEPENDENCY_SNAPSHOT_INVALID");
      }
      if (rawCrossDay instanceof Map<?, ?> crossDay) {
        crossDay.forEach((key, value) -> {
          if (key instanceof String alias) {
            pinnedCrossDay.put(alias, value);
          }
        });
      }
      String mergedPayload = mergeCrossDayPayload(sourcePayload, pinnedCrossDay);
      return CrossDayDependencyOutcome.proceed(mergedPayload);
    } catch (RuntimeException invalidSnapshot) {
      return failRerunCrossDaySnapshot(rerunRun, node, "DEPENDENCY_SNAPSHOT_INVALID");
    }
  }

  private CrossDayDependencyOutcome failRerunCrossDaySnapshot(
      WorkflowRunEntity workflowRun, WorkflowDagService.DagNodeResolution node, String reason) {
    writeFailedNodeRun(workflowRun.getId(), node, "CROSS_DAY_RERUN_" + reason);
    log.warn(
        "workflow_node JOB_RERUN cross-day snapshot rejected: tenantId={}, workflowRunId={},"
            + " nodeCode={}, reason={}",
        workflowRun.getTenantId(),
        workflowRun.getId(),
        node.nodeCode(),
        reason);
    return CrossDayDependencyOutcome.halted();
  }

  private boolean isJobRerun(String sourcePayload) {
    Map<String, Object> params = WorkflowNodePayloadBuilder.parsePayloadMap(sourcePayload);
    return "JOB_RERUN".equals(params.get("operationType"));
  }

  private boolean sameDependencySpec(String sourceSpec, String currentSpec) {
    if (EmptyChecks.isBlank(sourceSpec) || EmptyChecks.isBlank(currentSpec)) {
      return Objects.equals(sourceSpec, currentSpec);
    }
    Object source = JsonUtils.fromJson(sourceSpec, Object.class);
    Object current = JsonUtils.fromJson(currentSpec, Object.class);
    return Objects.equals(source, current);
  }

  private Long positiveLong(Object value) {
    try {
      String numericText = String.valueOf(value);
      long parsed = Long.parseLong(numericText);
      return parsed > 0 ? parsed : null;
    } catch (RuntimeException invalid) {
      return null;
    }
  }

  /** 把 resolver 返回的 alias map 合并到 sourcePayload 的 {@code crossDay} 字段下，供 ADR-009 DSL 引用。 */
  @SuppressWarnings("unchecked")
  private String mergeCrossDayPayload(String sourcePayload, Map<String, Object> crossDay) {
    if (crossDay == null || crossDay.isEmpty()) {
      return sourcePayload;
    }
    Map<String, Object> base;
    if (EmptyChecks.isBlank(sourcePayload)) {
      base = new LinkedHashMap<>();
    } else {
      Object parsedPayload = JsonUtils.fromJson(sourcePayload, Map.class);
      Map<String, Object> payloadFields = (Map<String, Object>) parsedPayload;
      base = new LinkedHashMap<>(payloadFields);
    }
    base.put("crossDay", crossDay);
    return JsonUtils.toJson(base);
  }

  private void writeWaitingDependencyNodeRun(
      Long workflowRunId,
      WorkflowDagService.DagNodeResolution node,
      List<String> waitingReasons,
      String sourcePayload) {
    WorkflowNodeRunEntity waiting = new WorkflowNodeRunEntity();
    waiting.setWorkflowRunId(workflowRunId);
    waiting.setNodeCode(node.nodeCode());
    waiting.setNodeType(node.nodeType());
    int nextRunSequence = WorkflowNodeRunSupport.nextRunSeq(
        workflowMappers.workflowNodeRunMapper, workflowRunId, node.nodeCode());
    waiting.setRunSeq(nextRunSequence);
    waiting.setNodeStatus(WorkflowNodeRunStatus.WAITING_DEPENDENCY.code());
    waiting.setRetryCount(0);
    waiting.setDurationMs(0L);
    waiting.setErrorCode("CROSS_DAY_DEP_WAITING");
    List<String> resolvedWaitingReasons =
        EmptyChecks.isNull(waitingReasons) ? List.of() : waitingReasons;
    waiting.setErrorMessage(String.join("; ", resolvedWaitingReasons));
    // ADR-018 Stage 7: 显式记录等待起点，reconciler 计算超时窗口
    waiting.setStartedAt(BatchDateTimeSupport.utcNow());
    String protectedSourcePayload = protectSourcePayload(sourcePayload);
    waiting.setSourcePayloadProtected(protectedSourcePayload);
    workflowMappers.workflowNodeRunMapper.insert(waiting);
  }

  private void writeFailedNodeRun(
      Long workflowRunId, WorkflowDagService.DagNodeResolution node, String failureCode) {
    WorkflowNodeRunEntity failed = new WorkflowNodeRunEntity();
    failed.setWorkflowRunId(workflowRunId);
    failed.setNodeCode(node.nodeCode());
    failed.setNodeType(node.nodeType());
    int nextRunSequence = WorkflowNodeRunSupport.nextRunSeq(
        workflowMappers.workflowNodeRunMapper, workflowRunId, node.nodeCode());
    failed.setRunSeq(nextRunSequence);
    failed.setNodeStatus(WorkflowNodeRunStatus.FAILED.code());
    failed.setRetryCount(0);
    failed.setDurationMs(0L);
    failed.setErrorCode(failureCode);
    failed.setErrorMessage("cross-day dependency resolve failed");
    // BUG-2：禁用 Instant.now()，统一走 BatchDateTimeSupport（其他时间字段均如此）
    failed.setFinishedAt(BatchDateTimeSupport.utcNow());
    workflowMappers.workflowNodeRunMapper.insert(failed);
  }

  /**
   * 跨日依赖解析的三态结果包装。RESOLVED / WAITING / FAILED 由 caller 决定是否继续 dispatch。
   *
   * <p>{@code halt} 字段：true 表示"调用方该 short circuit 返回 0"（WAITING / FAILED）；false 表示 dispatch 应继续，使用
   * {@code effectivePayload}（合并了 cross-day map 的 payload）。
   */
  private record CrossDayDependencyOutcome(boolean halt, String effectivePayload) {
    static CrossDayDependencyOutcome proceed(String payload) {
      return new CrossDayDependencyOutcome(false, payload);
    }

    static CrossDayDependencyOutcome halted() {
      return new CrossDayDependencyOutcome(true, null);
    }
  }

  private int dispatchTaskNode(
      JobInstanceEntity jobInstance,
      WorkflowRunEntity workflowRun,
      WorkflowDagService.DagNodeResolution node,
      WorkflowNodeEntity workflowNode,
      String sourcePayload,
      String traceId) {
    WorkflowNodeRunEntity nodeRun =
        recordNodeRunReady(workflowRun.getId(), node.nodeCode(), node.nodeType(), sourcePayload);
    if (EmptyChecks.isNull(nodeRun)) {
      return 0;
    }
    String targetJobCode = workflowNode.getRelatedJobCode() == null
            || workflowNode.getRelatedJobCode().isBlank()
        ? jobInstance.getJobCode()
        : workflowNode.getRelatedJobCode();
    // 下游健康与资源画像必须基于 worker 最终收到的参数判定。先合并根参数、上游产出和
    // node_params（含 ADR-009 引用），再用同一份载荷构造计划，避免 DAG 节点静态配置的
    // channelCode/resourceProfile 在准入完成后才出现而绕过门禁。
    String baseTaskPayload = payloadBuilder.buildTaskPayload(
        sourcePayload, node, targetJobCode, workflowNode, jobInstance, workflowRun);
    SchedulePlan plan = buildTaskNodeSchedulePlan(baseTaskPayload, jobInstance, targetJobCode);
    boolean planIsMissingOrHasNoPartitions = EmptyChecks.isNull(plan)
        || EmptyChecks.isNull(plan.getPartitions())
        || EmptyChecks.isEmpty(Objects.requireNonNull(plan.getPartitions()));
    if (planIsMissingOrHasNoPartitions) {
      return 0;
    }
    // R7 log-audit-bug: 当 targetJobCode 在 job_definition 不存在 / 已 disabled 时，
    // SchedulePlanBuilder 仍会返回 plan 但 defaultWorkerType=null（jobDefinition 未命中）；
    // 之前会照常进入 createTask → INSERT job_task → 撞 NOT NULL 抛 PSQLException
    // → 整个 TriggerLaunchConsumer 无限循环重试 → DLQ 跳过。这里 fail-fast 拒绝派发，
    // 把节点 ready 状态留给重试 / 运维补 job_definition 后再次触发。
    String defaultWorkerType = plan.getDefaultWorkerType();
    if (EmptyChecks.isBlank(defaultWorkerType)) {
      log.error(
          "workflow node task_type unresolved (job_definition missing or disabled): "
              + "tenantId={} workflowRunId={} nodeCode={} targetJobCode={}",
          jobInstance.getTenantId(),
          workflowRun.getId(),
          node.nodeCode(),
          targetJobCode);
      throw BizException.of(
          ResultCode.NOT_FOUND,
          "error.workflow_node.related_job_definition_missing",
          targetJobCode);
    }
    baseTaskPayload = payloadBuilder.pinSystemPipelineIdentity(baseTaskPayload, defaultWorkerType);
    applyTaskNodeSchedulingOverrides(plan, workflowNode, jobInstance);
    // P1 动态 fan-out:节点配了 fanOut → 按上游 output 数组展开成 N 个并行分区(在资源调度前展开,
    // N 个分区都参与 worker 路由)。复用现有 partition 派发 + 聚合(N partition 终态聚合成节点终态),不另造状态机。
    FanOutPlan fanOut = prepareFanOut(workflowNode, workflowRun, node.nodeCode(), plan);
    ResourceSchedulingRequest schedulingRequest = SchedulePlanSupport.toSchedulingRequest(plan);
    schedulingRequest.setNewJobAdmission(
        JobInstanceStatus.CREATED.code().equals(jobInstance.getInstanceStatus()));
    ResourceSchedulingDecision decision = resourceScheduler.schedule(schedulingRequest);
    if (isRejected(decision)) {
      throw BizException.of(
          ResultCode.BUSINESS_ERROR,
          "error.partition.dispatch_business_error",
          decision.getReasonCode(),
          decision.getReasonMessage());
    }
    SchedulePlanSupport.applySchedulingDecision(plan, decision);
    assignNodePartitionIdentity(jobInstance, node, plan, targetJobCode);
    List<JobPartitionEntity> newPartitions = partitionLifecycleService.createPartitions(
        plan, jobInstance.getId(), decision.getPartitionStatus());
    int sequence = 1;
    int fanOutIndex = 0;
    for (JobPartitionEntity partition : newPartitions) {
      JobTaskEntity task = new JobTaskEntity();
      task.setTenantId(jobInstance.getTenantId());
      task.setJobInstanceId(jobInstance.getId());
      task.setJobInstanceBizDate(jobInstance.getBizDate());
      task.setJobPartitionId(partition.getId());
      task.setTaskType(plan.getDefaultWorkerType());
      task.setTaskSeq(sequence++);
      String selectedWorkerId = resolveSelectedWorkerId(plan, partition);
      task.setAssignedWorkerCode(selectedWorkerId);
      task.setTaskStatus(decision.getTaskStatus());
      task.setVersion(0L);
      Integer taskPriority = DryRunSchedulingPriority.resolve(plan.isDryRun(), plan.getPriority());
      task.setPriority(taskPriority);
      // fan-out:每个并行分区拿不同的 item(注入到 payload),非 fan-out 走共享 payload(行为不变)。
      String taskPayload = fanOut == null
          ? baseTaskPayload
          : WorkflowFanOutSupport.injectItem(
              baseTaskPayload,
              fanOut.itemParam(),
              fanOut.items().get(fanOutIndex),
              fanOutIndex,
              fanOut.items().size());
      fanOutIndex++;
      task.setTaskPayload(taskPayload);
      task.setDryRun(plan.isDryRun());
      // ORCH-P4-2：派单期把节点 startToClose timeout 拷到 task，让 TaskTimeoutEnforcer 自洽扫 job_task。
      task.setTaskTimeoutSeconds(workflowNode.getTaskTimeoutSeconds());
      taskCreationService.createTask(task);
      boolean partitionWasReleasedForDispatch = decision.isDispatchable()
          && partitionLifecycleService.releaseForDispatch(
              partition, task, PartitionStatus.CREATED.code(), TaskStatus.CREATED.code());
      if (partitionWasReleasedForDispatch) {
        String dispatchEventKey = OutboxEventKeyGenerator.forWorkflowNodeDispatch(
            jobInstance.getTenantId(), workflowRun.getId(), node.nodeCode(), task.getId());
        taskDispatchOutboxService.writeDispatchEvent(
            jobInstance, task, partition, traceId, dispatchEventKey);
      }
    }
    int currentExpectedPartitionCount = jobInstance.getExpectedPartitionCount() == null
        ? 0
        : jobInstance.getExpectedPartitionCount();
    int updated = jobMappers.jobInstanceMapper.updateExpectedPartitionCount(
        jobInstance.getTenantId(),
        jobInstance.getId(),
        currentExpectedPartitionCount + newPartitions.size(),
        jobInstance.getVersion());
    if (updated <= 0) {
      throw new IllegalStateException("job instance expected partition count update conflict");
    }
    jobInstance.setExpectedPartitionCount(currentExpectedPartitionCount + newPartitions.size());
    jobInstance.setVersion((jobInstance.getVersion() == null ? 0L : jobInstance.getVersion()) + 1);
    return newPartitions.size();
  }

  /** 资源准入和任务载荷共用已合并参数，避免节点配置晚于准入才生效。 */
  private SchedulePlan buildTaskNodeSchedulePlan(
      String baseTaskPayload, JobInstanceEntity jobInstance, String targetJobCode) {
    String tenantId = jobInstance.getTenantId();
    String bizDate = jobInstance.getBizDate().toString();
    Map<String, Object> taskPayloadAttributes =
        WorkflowNodePayloadBuilder.parsePayloadMap(baseTaskPayload);
    SchedulePlanCommand planCommand =
        new SchedulePlanCommand(tenantId, targetJobCode, bizDate, taskPayloadAttributes);
    return schedulePlanBuilder.build(planCommand);
  }

  /** 节点只覆盖已显式指定的窗口和 worker 组，未指定值继续使用作业计划。 */
  private void applyTaskNodeSchedulingOverrides(
      SchedulePlan plan, WorkflowNodeEntity workflowNode, JobInstanceEntity jobInstance) {
    plan.setDryRun(Boolean.TRUE.equals(jobInstance.getDryRun()));
    String windowCode = workflowNode.getWindowCode();
    plan.setWindowCode(EmptyChecks.isBlank(windowCode) ? plan.getWindowCode() : windowCode);
    boolean hasNonBlankWorkerGroupOverride = EmptyChecks.isNotNull(workflowNode.getWorkerGroup())
        && !EmptyChecks.isBlank(Objects.requireNonNull(workflowNode.getWorkerGroup()));
    if (hasNonBlankWorkerGroupOverride) {
      plan.setWorkerGroup(workflowNode.getWorkerGroup());
    }
  }

  /** 节点分区接在实例已有编号之后，保留节点后缀以隔离幂等与业务身份。 */
  private void assignNodePartitionIdentity(
      JobInstanceEntity jobInstance,
      WorkflowDagService.DagNodeResolution node,
      SchedulePlan plan,
      String targetJobCode) {
    JobPartitionQuery existingPartitionQuery =
        JobPartitionQuery.ofJobInstance(jobInstance.getTenantId(), jobInstance.getId());
    List<JobPartitionEntity> existingPartitions =
        jobMappers.jobPartitionMapper.selectByQuery(existingPartitionQuery);
    int nextPartitionNo = existingPartitions.stream()
            .map(JobPartitionEntity::getPartitionNo)
            .filter(Objects::nonNull)
            .max(Integer::compareTo)
            .orElse(0)
        + 1;
    for (SchedulePlan.PartitionPlan partitionPlan : plan.getPartitions()) {
      partitionPlan.setPartitionNo(nextPartitionNo++);
      partitionPlan.setPartitionKey(partitionPlan.getPartitionKey()
          + ":"
          + node.nodeCode()
          + ":"
          + partitionPlan.getPartitionNo());
      partitionPlan.setBusinessKey((EmptyChecks.isNull(partitionPlan.getBusinessKey())
              ? targetJobCode
              : partitionPlan.getBusinessKey())
          + ":"
          + node.nodeCode());
    }
  }

  /** fan-out 解析结果:N 个 item + 注入用的 itemParam。null 表示普通(非 fan-out)节点。 */
  private record FanOutPlan(List<Object> items, String itemParam) {}

  /**
   * 解析 + 校验 fan-out,并就地把 {@code plan} 的分区展开成 N 份。无 fanOut 配置 → 返回 null(普通节点)。 空数组 / 超 maxFanOut →
   * fail-fast。
   */
  private FanOutPlan prepareFanOut(
      WorkflowNodeEntity workflowNode,
      WorkflowRunEntity workflowRun,
      String nodeCode,
      SchedulePlan plan) {
    WorkflowFanOutSupport.FanOutSpec spec = WorkflowFanOutSupport.parseSpec(workflowNode);
    if (spec == null) {
      return null;
    }
    List<Object> items = payloadBuilder.resolveFanOutItems(spec.itemsExpr(), workflowRun);
    if (items.isEmpty()) {
      // v0.1:空数组 fail-fast(上游须保证非空,或用 GATEWAY 守护)。v0.2 再支持空 fan-out 直接 SUCCESS。
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.workflow.fan_out_items_empty", nodeCode);
    }
    if (items.size() > spec.maxFanOut()) {
      String actualFanOutCount = String.valueOf(items.size());
      String configuredFanOutLimit = String.valueOf(spec.maxFanOut());
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.workflow.fan_out_exceeds_max",
          actualFanOutCount,
          configuredFanOutLimit);
    }
    List<SchedulePlan.PartitionPlan> partitions =
        WorkflowFanOutSupport.expandPartitions(plan, items.size());
    WorkflowFanOutSupport.bindDispatchTargetRefs(plan, items, partitions);
    plan.setPartitionCount(items.size());
    return new FanOutPlan(items, spec.itemParam());
  }

  /**
   * GATEWAY / START 节点无实际工作负载，合成一条 RUNNING→SUCCESS 的 node_run（同事务），再按条件解析下游节点 并递归 dispatch。这样 DAG
   * 流转不会在无作业节点处停滞等待 worker 回报。END 节点在下游解析时就地写 SUCCESS node_run，不再递归。
   */
  private int dispatchGatewayNode(
      JobInstanceEntity jobInstance,
      WorkflowRunEntity workflowRun,
      WorkflowDagService.DagNodeResolution node,
      String sourcePayload) {
    Instant now = BatchDateTimeSupport.utcNow();
    WorkflowNodeRunEntity runningNode = new WorkflowNodeRunEntity();
    runningNode.setWorkflowRunId(workflowRun.getId());
    runningNode.setNodeCode(node.nodeCode());
    runningNode.setNodeType(node.nodeType());
    int nextRunSequence = WorkflowNodeRunSupport.nextRunSeq(
        workflowMappers.workflowNodeRunMapper, workflowRun.getId(), node.nodeCode());
    runningNode.setRunSeq(nextRunSequence);
    runningNode.setNodeStatus(WorkflowNodeRunStatus.RUNNING.code());
    runningNode.setRetryCount(0);
    runningNode.setStartedAt(now);
    runningNode.setDurationMs(0L);
    workflowMappers.workflowNodeRunMapper.insert(runningNode);
    workflowMappers.workflowNodeRunMapper.updateStatus(UpdateNodeRunStatusParam.builder()
        .id(runningNode.getId())
        .nodeStatus(WorkflowNodeRunStatus.SUCCESS.code())
        .errorCode(null)
        .errorMessage(null)
        .durationMs(0L)
        .finishedAt(now)
        .build());
    List<WorkflowDagService.DagNodeResolution> nextNodes =
        workflowDagService.resolveNextNodesForRun(
            workflowRun, node.nodeCode(), true, sourcePayload);
    int dispatchedCount = 0;
    for (WorkflowDagService.DagNodeResolution nextNode : nextNodes) {
      if (WorkflowNodeCode.END.code().equals(nextNode.nodeCode())) {
        createTerminalNodeRun(workflowRun.getId(), nextNode, now);
        continue;
      }
      dispatchedCount += dispatchNode(
          jobInstance,
          workflowRun,
          nextNode,
          sourcePayload,
          EmptyChecks.isNull(jobInstance) ? workflowRun.getTraceId() : jobInstance.getTraceId());
    }
    workflowDagService.cascadeSkipDownstreamForRun(workflowRun, node.nodeCode(), sourcePayload);
    workflowNodeTerminalFinalizer.finalizeIfAllNodesTerminal(workflowRun);
    return dispatchedCount;
  }

  private void createTerminalNodeRun(
      Long workflowRunId, WorkflowDagService.DagNodeResolution nextNode, Instant finishedAt) {
    WorkflowNodeRunEntity terminalNode = new WorkflowNodeRunEntity();
    terminalNode.setWorkflowRunId(workflowRunId);
    terminalNode.setNodeCode(nextNode.nodeCode());
    terminalNode.setNodeType(nextNode.nodeType());
    int nextRunSequence = WorkflowNodeRunSupport.nextRunSeq(
        workflowMappers.workflowNodeRunMapper, workflowRunId, nextNode.nodeCode());
    terminalNode.setRunSeq(nextRunSequence);
    terminalNode.setNodeStatus(WorkflowNodeRunStatus.SUCCESS.code());
    terminalNode.setRetryCount(0);
    terminalNode.setStartedAt(finishedAt);
    terminalNode.setFinishedAt(finishedAt);
    terminalNode.setDurationMs(0L);
    workflowMappers.workflowNodeRunMapper.insert(terminalNode);
  }

  private int recordRejectedChildJobLaunch(
      WorkflowRunEntity workflowRun,
      WorkflowDagService.DagNodeResolution node,
      String sourcePayload,
      String traceId,
      ChildJobLaunchSupport.RejectedChildJobLaunchException failure) {
    Instant finishedAt = BatchDateTimeSupport.utcNow();
    BizException rejection = failure.rejection();
    UpdateNodeRunStatusParam.UpdateNodeRunStatusParamBuilder updateParamBuilder =
        UpdateNodeRunStatusParam.builder()
            .id(failure.nodeRunId())
            .nodeStatus(WorkflowNodeRunStatus.FAILED.code())
            .errorCode(rejection.getCode().code())
            .errorMessage(rejection.getMessage())
            .errorKey(rejection.getMessageKey());
    Object[] messageArgs = rejection.getMessageArgs();
    String errorArgs = EmptyChecks.isNull(messageArgs) ? null : JsonUtils.toJson(messageArgs);
    int updated = workflowMappers.workflowNodeRunMapper.updateStatus(updateParamBuilder
        .errorArgs(errorArgs)
        .durationMs(0L)
        .finishedAt(finishedAt)
        .build());
    if (updated == 0) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.workflow.child_job_launch_rejected");
    }
    List<WorkflowDagService.DagNodeResolution> nextNodes =
        workflowDagService.resolveNextNodesForRun(
            workflowRun, node.nodeCode(), false, sourcePayload);
    int dispatchedCount = 0;
    for (WorkflowDagService.DagNodeResolution nextNode : nextNodes) {
      if (WorkflowNodeCode.END.code().equals(nextNode.nodeCode())) {
        createTerminalNodeRun(workflowRun.getId(), nextNode, finishedAt);
        continue;
      }
      dispatchedCount += dispatchNode(null, workflowRun, nextNode, sourcePayload, traceId);
    }
    workflowDagService.cascadeSkipDownstreamForRun(workflowRun, node.nodeCode(), sourcePayload);
    workflowNodeTerminalFinalizer.finalizeIfAllNodesTerminal(workflowRun);
    return dispatchedCount;
  }

  private boolean isGatewayNode(String nodeType) {
    return WorkflowNodeType.GATEWAY.code().equalsIgnoreCase(nodeType)
        || WorkflowNodeType.START.code().equalsIgnoreCase(nodeType);
  }

  private boolean isJobNode(String nodeType) {
    return WorkflowNodeType.JOB.code().equalsIgnoreCase(nodeType);
  }

  private boolean isWaitNode(String nodeType) {
    return WorkflowNodeType.WAIT.code().equalsIgnoreCase(nodeType);
  }

  private boolean isNodeAlreadyActivated(Long workflowRunId, String nodeCode) {
    // C-3: 行锁防止 isNodeAlreadyActivated 与 createPartitions 之间的 TOCTOU 竞态
    WorkflowNodeRunEntity latestNodeRun =
        workflowMappers.workflowNodeRunMapper.selectLatestForUpdate(workflowRunId, nodeCode);
    if (latestNodeRun == null) {
      return false;
    }
    String nodeStatus = latestNodeRun.getNodeStatus();
    return WorkflowNodeRunStatus.READY.code().equals(nodeStatus)
        || WorkflowNodeRunStatus.RUNNING.code().equals(nodeStatus)
        || WorkflowNodeRunStatus.SUCCESS.code().equals(nodeStatus);
  }

  private boolean isRejected(ResourceSchedulingDecision decision) {
    return decision != null
        && (ResourceAdmissionAction.REJECT == decision.getAdmissionAction()
            || decision.isFailFast());
  }

  private String resolveSelectedWorkerId(SchedulePlan plan, JobPartitionEntity partition) {
    boolean partitionHasAssignedWorker = EmptyChecks.isNotNull(partition)
        && EmptyChecks.isNotNull(partition.getWorkerCode())
        && !EmptyChecks.isBlank(Objects.requireNonNull(partition.getWorkerCode()));
    if (partitionHasAssignedWorker) {
      return partition.getWorkerCode();
    }
    if (plan != null && plan.getDefaultWorkerRoute() != null) {
      return plan.getDefaultWorkerRoute().getWorkerCode();
    }
    return null;
  }

  private WorkflowNodeRunEntity recordNodeRunReady(
      Long workflowRunId, String nodeCode, String nodeType, String sourcePayload) {
    WorkflowNodeRunMapper nodeRunMapper = workflowMappers.workflowNodeRunMapper;
    String protectedSourcePayload = protectSourcePayload(sourcePayload);
    return WorkflowNodeRunSupport.recordReady(
        nodeRunMapper, workflowRunId, nodeCode, nodeType, protectedSourcePayload);
  }

  private String protectSourcePayload(String sourcePayload) {
    return EmptyChecks.isBlank(sourcePayload)
        ? null
        : secretPayloadProtector.protect(sourcePayload);
  }
}
