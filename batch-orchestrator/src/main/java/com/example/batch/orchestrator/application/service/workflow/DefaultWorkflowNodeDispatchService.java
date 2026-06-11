package com.example.batch.orchestrator.application.service.workflow;

import com.example.batch.common.enums.PartitionStatus;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.enums.WorkflowNodeCode;
import com.example.batch.common.enums.WorkflowNodeRunStatus;
import com.example.batch.common.enums.WorkflowNodeType;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.persistence.entity.WorkflowRunEntity;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.application.engine.OutboxEventKeyGenerator;
import com.example.batch.orchestrator.application.engine.TaskDispatchOutboxService;
import com.example.batch.orchestrator.application.plan.SchedulePlan;
import com.example.batch.orchestrator.application.plan.SchedulePlanBuilder;
import com.example.batch.orchestrator.application.plan.SchedulePlanCommand;
import com.example.batch.orchestrator.application.scheduler.ResourceScheduler;
import com.example.batch.orchestrator.application.service.task.ChildJobLaunchSupport;
import com.example.batch.orchestrator.application.service.task.OrchestratorJobMappers;
import com.example.batch.orchestrator.application.service.task.PartitionLifecycleService;
import com.example.batch.orchestrator.application.service.task.TaskExecutionService;
import com.example.batch.orchestrator.application.service.workflow.CrossDayDependencyResolver.ResolutionResult;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import com.example.batch.orchestrator.domain.param.UpdateNodeRunStatusParam;
import com.example.batch.orchestrator.domain.query.JobPartitionQuery;
import com.example.batch.orchestrator.domain.scheduling.ResourceSchedulingDecision;
import com.example.batch.orchestrator.domain.scheduling.ResourceSchedulingRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 工作流 DAG 节点派发：按节点类型分三条路径派发，所有路径的幂等由 {@link #isNodeAlreadyActivated} 配合 {@code workflow_node_run}
 * 行锁保证（见 {@code C-3}），防止同一节点被并发 dispatch 重复激活。
 *
 * <p>三种派发模式：
 *
 * <ul>
 *   <li><b>GATEWAY / START</b>：本身无工作负载，合成"立即完成"的 node_run（RUNNING→SUCCESS 同事务） 后按条件解析下游节点并递归
 *       dispatch，保证 DAG 流转不被无作业节点阻塞。
 *   <li><b>JOB</b>：通过启动一个独立子 Job 实例来派发，在父 Job 中插入一对"虚拟"分区 + 任务 （状态直接为 RUNNING），子 Job 终态时通过 params
 *       snapshot 中的 {@code _parentVirtualTaskId} 回指父任务，复用 {@code DefaultTaskOutcomeService} 基于分区的
 *       DAG 推进逻辑，避免再写一套子作业监听。
 *   <li><b>TASK / FILE_STEP</b>：普通节点，走 {@code SchedulePlanBuilder} 生成分片 + 资源调度决策 + 创建任务 + 落 outbox
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
  private final ObjectProvider<TaskExecutionService> taskExecutionServiceProvider;
  // P2-4 god-class-decomposition: payload 拼装 + 上游产物合并 + ADR-009 DSL 解析全套抽到 collaborator
  private final WorkflowNodePayloadBuilder payloadBuilder;
  // P2-4 god-class-decomposition: JOB 节点子作业拉起全套(virtual partition/task + 写 trigger_request +
  // 构造 child LaunchRequest + WORKFLOW_INTERNAL_PAYLOAD_KEYS) 抽到 collaborator
  private final ChildJobLaunchSupport childJobLaunchSupport;
  // ADR-018 跨批量日依赖解析；NULL（无依赖）跳过；REQUIRED 缺失 → WAITING_DEPENDENCY；解析失败 → FAILED
  private final CrossDayDependencyResolver crossDayDependencyResolver;

  @Lazy @Autowired private DefaultWorkflowNodeDispatchService self;

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
    if (jobInstance == null || workflowRun == null || node == null) {
      return 0;
    }
    // P1-4：先取 FOR UPDATE 行锁再做 readiness 检查。原顺序（先 readiness 后 lock）有 TOCTOU：
    // 两个上游同时上报 SUCCESS 触发 join 节点，两个线程可同时通过 readiness 检查、再去拿锁。
    // 调换顺序后，若存在历史 READY/RUNNING 记录，第二个线程在 FOR UPDATE 上阻塞或读取到已激活状态直接退出。
    // 残留 race（首次激活、节点 run 行尚未存在）由下游 unique 索引 / DuplicateKeyException catch 兜底。
    if (isNodeAlreadyActivated(workflowRun.getTenantId(), workflowRun.getId(), node.nodeCode())) {
      return 0;
    }
    if (!workflowDagService.isNodeReadyForDispatch(
        workflowRun.getId(),
        workflowRun.getWorkflowDefinitionId(),
        node.nodeCode(),
        sourcePayload)) {
      return 0;
    }
    WorkflowNodeEntity workflowNode =
        workflowMappers.workflowNodeMapper.selectByWorkflowDefinitionIdAndNodeCode(
            workflowRun.getWorkflowDefinitionId(), node.nodeCode());
    if (workflowNode == null) {
      return 0;
    }
    // ADR-018 §决策 §解析时机 — 跨批量日依赖解析；REQUIRED 缺失 → WAITING_DEPENDENCY；解析失败 → 节点 FAIL
    CrossDayDependencyOutcome outcome =
        evaluateCrossDayDependencies(jobInstance, workflowRun, workflowNode, node, sourcePayload);
    if (outcome.halt()) {
      return 0;
    }
    String effectivePayload = outcome.effectivePayload();
    if (isGatewayNode(workflowNode.getNodeType())) {
      return dispatchGatewayNode(jobInstance, workflowRun, node, effectivePayload);
    }
    if (isJobNode(workflowNode.getNodeType())) {
      return childJobLaunchSupport.dispatchJobNode(
          jobInstance, workflowRun, node, workflowNode, effectivePayload, traceId);
    }
    return dispatchTaskNode(
        jobInstance, workflowRun, node, workflowNode, effectivePayload, traceId);
  }

  /**
   * ADR-018 解析钩子。WAITING / FAILED 时写 {@link WorkflowNodeRunStatus#WAITING_DEPENDENCY} 或 {@link
   * WorkflowNodeRunStatus#FAILED} 节点 run，并 short-circuit dispatchNode 返回 0；RESOLVED 时把 resolved map
   * 注入 source payload 的 {@code crossDay} 字段（沿用 ADR-009 DSL 引用）。
   */
  private CrossDayDependencyOutcome evaluateCrossDayDependencies(
      JobInstanceEntity jobInstance,
      WorkflowRunEntity workflowRun,
      WorkflowNodeEntity workflowNode,
      WorkflowDagService.DagNodeResolution node,
      String sourcePayload) {
    String jsonSpec = workflowNode.getCrossDayDependencies();
    if (jsonSpec == null || jsonSpec.isBlank()) {
      return CrossDayDependencyOutcome.proceed(sourcePayload);
    }
    ResolutionResult result =
        crossDayDependencyResolver.resolve(
            jobInstance.getTenantId(), jobInstance.getBizDate(), jsonSpec);
    if (result.isResolved()) {
      return CrossDayDependencyOutcome.proceed(
          mergeCrossDayPayload(sourcePayload, result.getResolved()));
    }
    if (result.isWaiting()) {
      writeWaitingDependencyNodeRun(workflowRun.getId(), node, result.getWaitingReasons());
      log.info(
          "workflow_node WAITING_DEPENDENCY: tenantId={}, workflowRunId={}, nodeCode={},"
              + " reasons={}",
          jobInstance.getTenantId(),
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
        jobInstance.getTenantId(),
        workflowRun.getId(),
        node.nodeCode(),
        result.getFailureCode());
    return CrossDayDependencyOutcome.halted();
  }

  /** 把 resolver 返回的 alias map 合并到 sourcePayload 的 {@code crossDay} 字段下，供 ADR-009 DSL 引用。 */
  @SuppressWarnings("unchecked")
  private String mergeCrossDayPayload(String sourcePayload, Map<String, Object> crossDay) {
    if (crossDay == null || crossDay.isEmpty()) {
      return sourcePayload;
    }
    Map<String, Object> base =
        sourcePayload == null || sourcePayload.isBlank()
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(
                (Map<String, Object>) JsonUtils.fromJson(sourcePayload, Map.class));
    base.put("crossDay", crossDay);
    return JsonUtils.toJson(base);
  }

  private void writeWaitingDependencyNodeRun(
      Long workflowRunId, WorkflowDagService.DagNodeResolution node, List<String> waitingReasons) {
    WorkflowNodeRunEntity waiting = new WorkflowNodeRunEntity();
    waiting.setWorkflowRunId(workflowRunId);
    waiting.setNodeCode(node.nodeCode());
    waiting.setNodeType(node.nodeType());
    waiting.setRunSeq(nextRunSeq(workflowRunId, node.nodeCode()));
    waiting.setNodeStatus(WorkflowNodeRunStatus.WAITING_DEPENDENCY.code());
    waiting.setRetryCount(0);
    waiting.setDurationMs(0L);
    waiting.setErrorCode("CROSS_DAY_DEP_WAITING");
    waiting.setErrorMessage(String.join("; ", waitingReasons == null ? List.of() : waitingReasons));
    // ADR-018 Stage 7: 显式记录等待起点，reconciler 计算超时窗口
    waiting.setStartedAt(BatchDateTimeSupport.utcNow());
    workflowMappers.workflowNodeRunMapper.insert(waiting);
  }

  private void writeFailedNodeRun(
      Long workflowRunId, WorkflowDagService.DagNodeResolution node, String failureCode) {
    WorkflowNodeRunEntity failed = new WorkflowNodeRunEntity();
    failed.setWorkflowRunId(workflowRunId);
    failed.setNodeCode(node.nodeCode());
    failed.setNodeType(node.nodeType());
    failed.setRunSeq(nextRunSeq(workflowRunId, node.nodeCode()));
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
    recordNodeRunReady(workflowRun.getId(), node.nodeCode(), node.nodeType());
    String targetJobCode =
        workflowNode.getRelatedJobCode() == null || workflowNode.getRelatedJobCode().isBlank()
            ? jobInstance.getJobCode()
            : workflowNode.getRelatedJobCode();
    SchedulePlan plan =
        schedulePlanBuilder.build(
            new SchedulePlanCommand(
                jobInstance.getTenantId(),
                targetJobCode,
                jobInstance.getBizDate().toString(),
                WorkflowNodePayloadBuilder.parsePayloadMap(sourcePayload)));
    if (plan == null || plan.getPartitions() == null || plan.getPartitions().isEmpty()) {
      return 0;
    }
    // R7 log-audit-bug: 当 targetJobCode 在 job_definition 不存在 / 已 disabled 时，
    // SchedulePlanBuilder 仍会返回 plan 但 defaultWorkerType=null（jobDefinition 未命中）；
    // 之前会照常进入 createTask → INSERT job_task → 撞 NOT NULL 抛 PSQLException
    // → 整个 TriggerLaunchConsumer 死循环重试 → DLQ 跳过。这里 fail-fast 拒绝派发，
    // 把节点 ready 状态留给重试 / 运维补 job_definition 后再次触发。
    if (plan.getDefaultWorkerType() == null || plan.getDefaultWorkerType().isBlank()) {
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
    plan.setDryRun(Boolean.TRUE.equals(jobInstance.getDryRun()));
    plan.setWindowCode(
        workflowNode.getWindowCode() == null || workflowNode.getWindowCode().isBlank()
            ? plan.getWindowCode()
            : workflowNode.getWindowCode());
    if (workflowNode.getWorkerGroup() != null && !workflowNode.getWorkerGroup().isBlank()) {
      plan.setWorkerGroup(workflowNode.getWorkerGroup());
    }
    // P1 动态 fan-out:节点配了 fanOut → 按上游 output 数组展开成 N 个并行分区(在资源调度前展开,
    // N 个分区都参与 worker 路由)。复用现有 partition 派发 + 聚合(N partition 终态聚合成节点终态),不另造状态机。
    FanOutPlan fanOut = prepareFanOut(workflowNode, workflowRun, node.nodeCode(), plan);
    ResourceSchedulingDecision decision = resourceScheduler.schedule(buildSchedulingRequest(plan));
    applySchedulingDecision(plan, decision);
    List<JobPartitionEntity> existingPartitions =
        jobMappers.jobPartitionMapper.selectByQuery(
            new JobPartitionQuery(jobInstance.getTenantId(), jobInstance.getId(), null, null));
    int nextPartitionNo =
        existingPartitions.stream()
                .map(JobPartitionEntity::getPartitionNo)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0)
            + 1;
    for (SchedulePlan.PartitionPlan partitionPlan : plan.getPartitions()) {
      partitionPlan.setPartitionNo(nextPartitionNo++);
      partitionPlan.setPartitionKey(
          partitionPlan.getPartitionKey()
              + ":"
              + node.nodeCode()
              + ":"
              + partitionPlan.getPartitionNo());
      partitionPlan.setBusinessKey(
          (partitionPlan.getBusinessKey() == null ? targetJobCode : partitionPlan.getBusinessKey())
              + ":"
              + node.nodeCode());
    }
    List<JobPartitionEntity> newPartitions =
        partitionLifecycleService.createPartitions(
            plan, jobInstance.getId(), decision.getPartitionStatus());
    String baseTaskPayload =
        payloadBuilder.buildTaskPayload(
            sourcePayload, node, targetJobCode, workflowNode, jobInstance, workflowRun);
    int sequence = 1;
    int fanOutIndex = 0;
    for (JobPartitionEntity partition : newPartitions) {
      JobTaskEntity task = new JobTaskEntity();
      task.setTenantId(jobInstance.getTenantId());
      task.setJobInstanceId(jobInstance.getId());
      task.setJobPartitionId(partition.getId());
      task.setTaskType(plan.getDefaultWorkerType());
      task.setTaskSeq(sequence++);
      task.setAssignedWorkerCode(resolveSelectedWorkerId(plan, partition));
      task.setTaskStatus(decision.getTaskStatus());
      task.setVersion(0L);
      // V88: 拷 priority (workflow node task 的 priority 源 = SchedulePlan, 由
      // DefaultSchedulePlanBuilder 读 job_definition)
      task.setPriority(plan.getPriority());
      // fan-out:每个并行分区拿不同的 item(注入到 payload),非 fan-out 走共享 payload(行为不变)。
      String taskPayload =
          fanOut == null
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
      taskExecutionServiceProvider.getObject().createTask(task);
      if (decision.isDispatchable()
          && partitionLifecycleService.releaseForDispatch(
              partition, task, PartitionStatus.CREATED.code(), TaskStatus.CREATED.code())) {
        taskDispatchOutboxService.writeDispatchEvent(
            jobInstance,
            task,
            partition,
            traceId,
            OutboxEventKeyGenerator.forWorkflowNodeDispatch(
                jobInstance.getTenantId(), workflowRun.getId(), node.nodeCode(), task.getId()));
      }
    }
    int currentExpectedPartitionCount =
        jobInstance.getExpectedPartitionCount() == null
            ? 0
            : jobInstance.getExpectedPartitionCount();
    int updated =
        jobMappers.jobInstanceMapper.updateExpectedPartitionCount(
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
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.workflow.fan_out_exceeds_max",
          String.valueOf(items.size()),
          String.valueOf(spec.maxFanOut()));
    }
    plan.setPartitions(WorkflowFanOutSupport.expandPartitions(plan, items.size()));
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
    runningNode.setRunSeq(nextRunSeq(workflowRun.getId(), node.nodeCode()));
    runningNode.setNodeStatus(WorkflowNodeRunStatus.RUNNING.code());
    runningNode.setRetryCount(0);
    runningNode.setStartedAt(now);
    runningNode.setDurationMs(0L);
    workflowMappers.workflowNodeRunMapper.insert(runningNode);
    workflowMappers.workflowNodeRunMapper.updateStatus(
        UpdateNodeRunStatusParam.builder()
            .id(runningNode.getId())
            .nodeStatus(WorkflowNodeRunStatus.SUCCESS.code())
            .errorCode(null)
            .errorMessage(null)
            .durationMs(0L)
            .finishedAt(now)
            .build());
    List<WorkflowDagService.DagNodeResolution> nextNodes =
        workflowDagService.resolveNextNodes(
            workflowRun.getWorkflowDefinitionId(), node.nodeCode(), true, sourcePayload);
    int dispatchedCount = 0;
    for (WorkflowDagService.DagNodeResolution nextNode : nextNodes) {
      if (WorkflowNodeCode.END.code().equals(nextNode.nodeCode())) {
        createTerminalNodeRun(workflowRun.getId(), nextNode, now);
        continue;
      }
      dispatchedCount +=
          self.dispatchNode(
              jobInstance, workflowRun, nextNode, sourcePayload, jobInstance.getTraceId());
    }
    return dispatchedCount;
  }

  private void createTerminalNodeRun(
      Long workflowRunId, WorkflowDagService.DagNodeResolution nextNode, Instant finishedAt) {
    WorkflowNodeRunEntity terminalNode = new WorkflowNodeRunEntity();
    terminalNode.setWorkflowRunId(workflowRunId);
    terminalNode.setNodeCode(nextNode.nodeCode());
    terminalNode.setNodeType(nextNode.nodeType());
    terminalNode.setRunSeq(nextRunSeq(workflowRunId, nextNode.nodeCode()));
    terminalNode.setNodeStatus(WorkflowNodeRunStatus.SUCCESS.code());
    terminalNode.setRetryCount(0);
    terminalNode.setStartedAt(finishedAt);
    terminalNode.setFinishedAt(finishedAt);
    terminalNode.setDurationMs(0L);
    workflowMappers.workflowNodeRunMapper.insert(terminalNode);
  }

  private boolean isGatewayNode(String nodeType) {
    return WorkflowNodeType.GATEWAY.code().equalsIgnoreCase(nodeType)
        || WorkflowNodeType.START.code().equalsIgnoreCase(nodeType);
  }

  private boolean isJobNode(String nodeType) {
    return WorkflowNodeType.JOB.code().equalsIgnoreCase(nodeType);
  }

  private boolean isNodeAlreadyActivated(String tenantId, Long workflowRunId, String nodeCode) {
    // C-3: 行锁防止 isNodeAlreadyActivated 与 createPartitions 之间的 TOCTOU 竞态
    WorkflowNodeRunEntity latestNodeRun =
        workflowMappers.workflowNodeRunMapper.selectLatestForUpdate(
            tenantId, workflowRunId, nodeCode);
    if (latestNodeRun == null) {
      return false;
    }
    String nodeStatus = latestNodeRun.getNodeStatus();
    return WorkflowNodeRunStatus.READY.code().equals(nodeStatus)
        || WorkflowNodeRunStatus.RUNNING.code().equals(nodeStatus)
        || WorkflowNodeRunStatus.SUCCESS.code().equals(nodeStatus);
  }

  private ResourceSchedulingRequest buildSchedulingRequest(SchedulePlan plan) {
    ResourceSchedulingRequest request = new ResourceSchedulingRequest();
    request.setTenantId(plan.getTenantId());
    request.setJobCode(plan.getJobCode());
    request.setQueueCode(plan.getQueueCode());
    request.setWorkerGroup(plan.getWorkerGroup());
    request.setWorkerType(plan.getDefaultWorkerType());
    request.setWindowCode(plan.getWindowCode());
    request.setPriority(plan.getPriority());
    request.setRequestedPartitionCount(
        plan.getPartitionCount() == null ? 1 : plan.getPartitionCount());
    return request;
  }

  private void applySchedulingDecision(SchedulePlan plan, ResourceSchedulingDecision decision) {
    if (plan == null || decision == null) {
      return;
    }
    if (decision.getQueueCode() != null && !decision.getQueueCode().isBlank()) {
      plan.setQueueCode(decision.getQueueCode());
    }
    if (decision.getWorkerGroup() != null && !decision.getWorkerGroup().isBlank()) {
      plan.setWorkerGroup(decision.getWorkerGroup());
    }
    if (decision.getPriority() != null) {
      plan.setPriority(decision.getPriority());
    }
    if (decision.getRoute() != null) {
      plan.setDefaultWorkerRoute(decision.getRoute());
    }
    if (plan.getPartitions() == null) {
      return;
    }
    for (SchedulePlan.PartitionPlan partitionPlan : plan.getPartitions()) {
      partitionPlan.setPartitionStatus(decision.getPartitionStatus());
      if (decision.getRoute() != null) {
        partitionPlan.setWorkerRoute(decision.getRoute());
      }
    }
  }

  private String resolveSelectedWorkerId(SchedulePlan plan, JobPartitionEntity partition) {
    if (partition != null
        && partition.getWorkerCode() != null
        && !partition.getWorkerCode().isBlank()) {
      return partition.getWorkerCode();
    }
    if (plan != null && plan.getDefaultWorkerRoute() != null) {
      return plan.getDefaultWorkerRoute().getWorkerCode();
    }
    return null;
  }

  private void recordNodeRunReady(Long workflowRunId, String nodeCode, String nodeType) {
    WorkflowNodeRunEntity readyNode = new WorkflowNodeRunEntity();
    readyNode.setWorkflowRunId(workflowRunId);
    readyNode.setNodeCode(nodeCode);
    readyNode.setNodeType(nodeType);
    readyNode.setRunSeq(nextRunSeq(workflowRunId, nodeCode));
    readyNode.setNodeStatus(WorkflowNodeRunStatus.READY.code());
    readyNode.setRetryCount(0);
    readyNode.setDurationMs(0L);
    workflowMappers.workflowNodeRunMapper.insert(readyNode);
  }

  private int nextRunSeq(Long workflowRunId, String nodeCode) {
    WorkflowNodeRunEntity latestNodeRun =
        workflowMappers.workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(
            workflowRunId, nodeCode);
    return latestNodeRun == null || latestNodeRun.getRunSeq() == null
        ? 1
        : latestNodeRun.getRunSeq() + 1;
  }
}
