package com.example.batch.orchestrator.application.service;

import com.example.batch.common.constants.BatchStatusConstants;
import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.PartitionStatus;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.enums.WorkflowNodeCode;
import com.example.batch.common.enums.WorkflowNodeRunStatus;
import com.example.batch.common.enums.WorkflowNodeType;
import com.example.batch.common.persistence.entity.TriggerRequestEntity;
import com.example.batch.common.persistence.entity.WorkflowRunEntity;
import com.example.batch.common.utils.IdGenerator;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.application.engine.TaskDispatchOutboxService;
import com.example.batch.orchestrator.application.plan.SchedulePlan;
import com.example.batch.orchestrator.application.plan.SchedulePlanBuilder;
import com.example.batch.orchestrator.application.plan.SchedulePlanCommand;
import com.example.batch.orchestrator.application.scheduler.ResourceScheduler;
import com.example.batch.orchestrator.application.workflow.WorkflowParamResolver;
import com.example.batch.orchestrator.application.workflow.WorkflowRunContext;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import com.example.batch.orchestrator.domain.query.JobPartitionQuery;
import com.example.batch.orchestrator.domain.scheduler.ResourceSchedulingDecision;
import com.example.batch.orchestrator.domain.scheduler.ResourceSchedulingRequest;
import com.example.batch.orchestrator.mapper.UpdateNodeRunStatusParam;
import com.example.batch.orchestrator.service.LaunchService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
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
  private final ObjectProvider<LaunchService> launchServiceProvider;
  private final NamedParameterJdbcTemplate jdbcTemplate;
  // ADR-009 Stage 3: 派发前把 node_params 中的 $.nodes.<X>.output.<key> 引用替换为上游节点 output 实际值
  private final WorkflowParamResolver workflowParamResolver;

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
    if (isNodeAlreadyActivated(workflowRun.getId(), node.nodeCode())) {
      return 0;
    }
    if (isGatewayNode(workflowNode.getNodeType())) {
      return dispatchGatewayNode(jobInstance, workflowRun, node, sourcePayload);
    }
    if (isJobNode(workflowNode.getNodeType())) {
      return dispatchJobNode(jobInstance, workflowRun, node, workflowNode, sourcePayload, traceId);
    }
    return dispatchTaskNode(jobInstance, workflowRun, node, workflowNode, sourcePayload, traceId);
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
                parsePayloadMap(sourcePayload)));
    if (plan == null || plan.getPartitions() == null || plan.getPartitions().isEmpty()) {
      return 0;
    }
    plan.setWindowCode(
        workflowNode.getWindowCode() == null || workflowNode.getWindowCode().isBlank()
            ? plan.getWindowCode()
            : workflowNode.getWindowCode());
    if (workflowNode.getWorkerGroup() != null && !workflowNode.getWorkerGroup().isBlank()) {
      plan.setWorkerGroup(workflowNode.getWorkerGroup());
    }
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
    String taskPayload =
        buildTaskPayload(
            sourcePayload, node, targetJobCode, workflowNode, jobInstance, workflowRun);
    int sequence = 1;
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
      task.setTaskPayload(taskPayload);
      taskExecutionServiceProvider.getObject().createTask(task);
      if (decision.isDispatchable()
          && partitionLifecycleService.releaseForDispatch(
              partition, task, PartitionStatus.CREATED.code(), TaskStatus.CREATED.code())) {
        taskDispatchOutboxService.writeDispatchEvent(
            jobInstance,
            task,
            partition,
            traceId,
            jobInstance.getTenantId()
                + ":workflow:"
                + workflowRun.getId()
                + ":"
                + node.nodeCode()
                + ":"
                + task.getId());
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

  /**
   * GATEWAY / START 节点无实际工作负载，合成一条 RUNNING→SUCCESS 的 node_run（同事务），再按条件解析下游节点 并递归 dispatch。这样 DAG
   * 流转不会在无作业节点处停滞等待 worker 回报。END 节点在下游解析时就地写 SUCCESS node_run，不再递归。
   */
  private int dispatchGatewayNode(
      JobInstanceEntity jobInstance,
      WorkflowRunEntity workflowRun,
      WorkflowDagService.DagNodeResolution node,
      String sourcePayload) {
    Instant now = Instant.now();
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
          dispatchNode(jobInstance, workflowRun, nextNode, sourcePayload, jobInstance.getTraceId());
    }
    return dispatchedCount;
  }

  /**
   * 通过启动子实例来分发 JOB 节点。在父 Job 中创建"虚拟"分区和任务（状态=RUNNING）， 使 {@code DefaultTaskOutcomeService} 中基于分区的
   * DAG 推进逻辑能统一处理子 Job 完成信号。 子 Job 到达终态后通过 params snapshot 中的 {@code _parentVirtualTaskId} 回调。
   */
  private int dispatchJobNode(
      JobInstanceEntity jobInstance,
      WorkflowRunEntity workflowRun,
      WorkflowDagService.DagNodeResolution node,
      WorkflowNodeEntity workflowNode,
      String sourcePayload,
      String traceId) {
    String refJobCode = workflowNode.getRelatedJobCode();
    if (refJobCode == null || refJobCode.isBlank()) {
      return 0;
    }

    // 与 TASK/FILE_STEP 节点一致：立即将节点标为 READY
    recordNodeRunReady(workflowRun.getId(), node.nodeCode(), node.nodeType());

    String idempotencyKey =
        jobInstance.getTenantId() + ":wf:" + workflowRun.getId() + ":" + node.nodeCode();
    JobPartitionEntity virtualPartition =
        createVirtualPartition(jobInstance, node, refJobCode, idempotencyKey);
    JobTaskEntity virtualTask =
        createVirtualTask(jobInstance, node, refJobCode, virtualPartition, sourcePayload);
    incrementExpectedPartitionCount(jobInstance, node.nodeCode());

    // 为子作业写入 trigger_request，供 LaunchValidationService.load() 加载
    String childRequestId =
        writeTriggerRequestForChildJob(jobInstance, refJobCode, idempotencyKey, traceId);

    // 子作业启动参数含回指字段，便于子作业完成后回写本虚拟 task
    LaunchRequest childLaunchRequest =
        buildChildLaunchRequest(
            new ChildLaunchContext(
                jobInstance,
                workflowRun,
                node,
                refJobCode,
                sourcePayload,
                childRequestId,
                traceId,
                virtualTask,
                workflowNode));
    launchServiceProvider.getObject().launch(childLaunchRequest);

    return 1; // one virtual partition added to the parent job
  }

  private JobPartitionEntity createVirtualPartition(
      JobInstanceEntity jobInstance,
      WorkflowDagService.DagNodeResolution node,
      String refJobCode,
      String idempotencyKey) {
    List<JobPartitionEntity> existingPartitions =
        jobMappers.jobPartitionMapper.selectByQuery(
            new JobPartitionQuery(jobInstance.getTenantId(), jobInstance.getId(), null, null));
    int virtualPartitionNo =
        existingPartitions.stream()
                .map(JobPartitionEntity::getPartitionNo)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0)
            + 1;

    JobPartitionEntity virtualPartition = new JobPartitionEntity();
    virtualPartition.setTenantId(jobInstance.getTenantId());
    virtualPartition.setJobInstanceId(jobInstance.getId());
    virtualPartition.setPartitionNo(virtualPartitionNo);
    virtualPartition.setPartitionKey(node.nodeCode() + ":JOB:" + virtualPartitionNo);
    virtualPartition.setPartitionStatus(PartitionStatus.RUNNING.code());
    virtualPartition.setVersion(0L);
    virtualPartition.setRetryCount(0);
    virtualPartition.setBusinessKey(refJobCode + ":" + node.nodeCode());
    virtualPartition.setIdempotencyKey(idempotencyKey);
    jobMappers.jobPartitionMapper.insert(virtualPartition);
    return virtualPartition;
  }

  private JobTaskEntity createVirtualTask(
      JobInstanceEntity jobInstance,
      WorkflowDagService.DagNodeResolution node,
      String refJobCode,
      JobPartitionEntity virtualPartition,
      String sourcePayload) {
    Map<String, Object> payloadMap = new LinkedHashMap<>(parsePayloadMap(sourcePayload));
    payloadMap.put("workflowNodeCode", node.nodeCode());
    payloadMap.put("workflowNodeType", node.nodeType());
    payloadMap.put("targetJobCode", refJobCode);
    String taskPayload = JsonUtils.toJson(payloadMap);

    JobTaskEntity virtualTaskTemplate = new JobTaskEntity();
    virtualTaskTemplate.setTenantId(jobInstance.getTenantId());
    virtualTaskTemplate.setJobInstanceId(jobInstance.getId());
    virtualTaskTemplate.setJobPartitionId(virtualPartition.getId());
    virtualTaskTemplate.setTaskType("EXECUTION");
    virtualTaskTemplate.setTaskSeq(1);
    virtualTaskTemplate.setTaskStatus(TaskStatus.RUNNING.code());
    virtualTaskTemplate.setVersion(0L);
    virtualTaskTemplate.setTaskPayload(taskPayload);
    return taskExecutionServiceProvider.getObject().createTask(virtualTaskTemplate);
  }

  private void incrementExpectedPartitionCount(JobInstanceEntity jobInstance, String nodeCode) {
    int currentExpected =
        jobInstance.getExpectedPartitionCount() == null
            ? 0
            : jobInstance.getExpectedPartitionCount();
    int updated =
        jobMappers.jobInstanceMapper.updateExpectedPartitionCount(
            jobInstance.getTenantId(),
            jobInstance.getId(),
            currentExpected + 1,
            jobInstance.getVersion());
    if (updated <= 0) {
      throw new IllegalStateException(
          "job instance expected partition count update conflict for JOB node " + nodeCode);
    }
    jobInstance.setExpectedPartitionCount(currentExpected + 1);
    jobInstance.setVersion((jobInstance.getVersion() == null ? 0L : jobInstance.getVersion()) + 1);
  }

  private String writeTriggerRequestForChildJob(
      JobInstanceEntity jobInstance, String refJobCode, String idempotencyKey, String traceId) {
    String childRequestId = IdGenerator.newTraceId();
    String childDedupKey = idempotencyKey + ":child";
    TriggerRequestEntity triggerRequest = new TriggerRequestEntity();
    triggerRequest.setTenantId(jobInstance.getTenantId());
    triggerRequest.setRequestId(childRequestId);
    triggerRequest.setTriggerType(TriggerType.EVENT.code());
    triggerRequest.setJobCode(refJobCode);
    triggerRequest.setBizDate(jobInstance.getBizDate());
    triggerRequest.setDedupKey(childDedupKey);
    triggerRequest.setRequestStatus(BatchStatusConstants.ACCEPTED);
    triggerRequest.setTraceId(traceId);
    jobMappers.triggerRequestMapper.insert(triggerRequest);
    return childRequestId;
  }

  private record ChildLaunchContext(
      JobInstanceEntity jobInstance,
      WorkflowRunEntity workflowRun,
      WorkflowDagService.DagNodeResolution node,
      String refJobCode,
      String sourcePayload,
      String childRequestId,
      String traceId,
      JobTaskEntity virtualTask,
      WorkflowNodeEntity workflowNode) {}

  /**
   * sourcePayload 在 workflow 跨节点传递时会携带前一个节点由 {@link #buildTaskPayload} 写入的 workflow 内部字段（{@code
   * workflowNodeCode / workflowNodeType / targetJobCode} 等）。 这些字段是"当前节点"的标记，不应该泄露给下游节点的子作业——否则
   * EXPORT 节点的子作业会 看到 IMPORT 节点的 targetJobCode，用错 pipeline（表现为 "unsupported export stage code:
   * RECEIVE"）。
   */
  private static final Set<String> WORKFLOW_INTERNAL_PAYLOAD_KEYS =
      Set.of(
          "workflowNodeCode",
          "workflowNodeType",
          "targetJobCode",
          "_parentNodeCode",
          "_parentVirtualTaskId",
          "_parentWorkflowRunId",
          "parentInstanceId");

  private LaunchRequest buildChildLaunchRequest(ChildLaunchContext ctx) {
    Map<String, Object> parsed = parsePayloadMap(ctx.sourcePayload());
    Map<String, Object> childParams = new LinkedHashMap<>();
    parsed.forEach(
        (k, v) -> {
          if (!WORKFLOW_INTERNAL_PAYLOAD_KEYS.contains(k)) {
            childParams.put(k, v);
          }
        });
    // 与 TASK 节点的 buildTaskPayload 对齐：把 workflow_node.node_params 合并进子作业 launch
    // params。否则 JOB 节点在设计器里配的 templateCode / channelCode / seed payload 等字段
    // 永远无法传到子作业 job_instance.params_snapshot → worker 看不到 → import/export 凭空失败。
    mergeNodeParams(childParams, ctx.workflowNode(), ctx.workflowRun());
    childParams.put("parentInstanceId", ctx.jobInstance().getId());
    childParams.put("_parentVirtualTaskId", ctx.virtualTask().getId());
    childParams.put("_parentWorkflowRunId", ctx.workflowRun().getId());
    childParams.put("_parentNodeCode", ctx.node().nodeCode());
    return new LaunchRequest(
        ctx.jobInstance().getTenantId(),
        ctx.refJobCode(),
        ctx.jobInstance().getBizDate(),
        TriggerType.EVENT,
        ctx.childRequestId(),
        ctx.traceId(),
        childParams);
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

  /**
   * 组装下游节点的 task payload。分层优先级（后写覆盖前写）：
   *
   * <ol>
   *   <li>workflow 实例的 {@code sourcePayload}（整条链路共享的根 params）
   *   <li>上游兄弟分区产出（{@code job_partition.output_summary} 里带的 {@code fileId} 等），保证 SETTLE 生成的
   *       file_record id 自动流向 DISPATCH 节点，而不是靠每条 workflow 手工声明
   *   <li>当前节点的 {@code workflow_node.node_params}（节点级静态配置，如 DISPATCH 的 {@code channelCode}）
   *   <li>workflow 元数据（{@code workflowNodeCode / workflowNodeType / targetJobCode}），供 worker
   *       侧用作上下文日志、幂等键计算
   * </ol>
   */
  @SuppressWarnings("unchecked")
  private String buildTaskPayload(
      String sourcePayload,
      WorkflowDagService.DagNodeResolution node,
      String targetJobCode,
      WorkflowNodeEntity workflowNode,
      JobInstanceEntity jobInstance,
      WorkflowRunEntity workflowRun) {
    Map<String, Object> payload = new LinkedHashMap<>();
    if (sourcePayload != null && !sourcePayload.isBlank()) {
      try {
        Object payloadObject = JsonUtils.fromJson(sourcePayload, Object.class);
        if (payloadObject instanceof Map<?, ?> payloadMap) {
          payload.putAll((Map<String, Object>) payloadMap);
        } else {
          payload.put("upstreamPayload", payloadObject);
        }
      } catch (IllegalArgumentException exception) {
        payload.put("upstreamPayloadRaw", sourcePayload);
      }
    }
    mergeUpstreamPartitionOutputs(payload, jobInstance);
    mergeNodeParams(payload, workflowNode, workflowRun);
    payload.put("workflowNodeCode", node.nodeCode());
    payload.put("workflowNodeType", node.nodeType());
    payload.put("targetJobCode", targetJobCode);
    return JsonUtils.toJson(payload);
  }

  /**
   * 扫描当前 workflow_run 同一 job_instance 下已 SUCCESS 的兄弟分区的 {@code output_summary}， 把 {@code fileId} /
   * {@code fileCode} 这类跨节点常用字段挑出来塞进 payload。保守做法： 只挑已知少量字段（避免把 partition 内部诊断字段污染到 worker
   * payload）。多分区并存时最新成功的胜出。
   */
  @SuppressWarnings("unchecked")
  private void mergeUpstreamPartitionOutputs(
      Map<String, Object> payload, JobInstanceEntity jobInstance) {
    if (jobInstance == null || jobInstance.getId() == null) {
      return;
    }
    List<JobPartitionEntity> siblings =
        jobMappers.jobPartitionMapper.selectByQuery(
            new JobPartitionQuery(jobInstance.getTenantId(), jobInstance.getId(), null, null));
    if (siblings == null || siblings.isEmpty()) {
      return;
    }
    JobPartitionEntity latestSuccess = null;
    for (JobPartitionEntity p : siblings) {
      if (!PartitionStatus.SUCCESS.code().equals(p.getPartitionStatus())) {
        continue;
      }
      if (latestSuccess == null
          || (p.getFinishedAt() != null
              && latestSuccess.getFinishedAt() != null
              && p.getFinishedAt().isAfter(latestSuccess.getFinishedAt()))) {
        latestSuccess = p;
      }
    }
    if (latestSuccess != null && latestSuccess.getOutputSummary() != null) {
      try {
        Object outputObj = JsonUtils.fromJson(latestSuccess.getOutputSummary(), Object.class);
        if (outputObj instanceof Map<?, ?> outMap) {
          Map<String, Object> out = (Map<String, Object>) outMap;
          // 保守白名单：只把已知的跨节点常用字段挑出来
          for (String key : List.of("fileId", "fileCode", "batchNo", "recordCount", "bizDate")) {
            Object v = out.get(key);
            if (v != null && !payload.containsKey(key)) {
              payload.put(key, v);
            }
          }
        }
      } catch (IllegalArgumentException ignored) {
        // 跳过脏 outputSummary
      }
    }
    // 兜底：partition.output_summary 不含 fileId 时，通过 trace_id 或 batchNo 反查 file_record。
    // 两条独立的线索都要查：
    //   (a) trace_id - 本次 run 期间 EXPORT worker 新建的 file_record 会打同一 trace_id
    //   (b) source_ref = batchNo - 文件按 batchNo 幂等复用时，trace_id 不更新但 source_ref 一致
    //       （settlement-2026-04-22 这种业务上每日唯一的文件就是这种场景）
    if (!payload.containsKey("fileId") && jobInstance.getTenantId() != null) {
      Long fileId = null;
      if (jobInstance.getTraceId() != null && !jobInstance.getTraceId().isBlank()) {
        fileId = lookupFileIdByTraceId(jobInstance.getTenantId(), jobInstance.getTraceId());
      }
      if (fileId == null) {
        Object batchNo = payload.get("batchNo");
        if (batchNo != null && !String.valueOf(batchNo).isBlank()) {
          fileId = lookupFileIdBySourceRef(jobInstance.getTenantId(), String.valueOf(batchNo));
        }
      }
      if (fileId != null) {
        payload.put("fileId", String.valueOf(fileId));
      }
    }
  }

  private Long lookupFileIdByTraceId(String tenantId, String traceId) {
    return queryFileIdSingle(
        "select id from batch.file_record where tenant_id = :tenantId"
            + " and trace_id = :traceId and source_type = 'GENERATED'"
            + " order by id desc limit 1",
        new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("traceId", traceId));
  }

  private Long lookupFileIdBySourceRef(String tenantId, String sourceRef) {
    return queryFileIdSingle(
        "select id from batch.file_record where tenant_id = :tenantId"
            + " and source_ref = :sourceRef and source_type = 'GENERATED'"
            + " order by id desc limit 1",
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("sourceRef", sourceRef));
  }

  private Long queryFileIdSingle(String sql, MapSqlParameterSource params) {
    try {
      return jdbcTemplate.queryForObject(sql, params, Long.class);
    } catch (EmptyResultDataAccessException ignored) {
      return null;
    } catch (RuntimeException ex) {
      log.warn(
          "file_record lookup failed: params={}, error={}", params.getValues(), ex.getMessage());
      return null;
    }
  }

  /**
   * 合并当前节点的 {@code workflow_node.node_params}（JSON 对象）到 payload。用户在 workflow 设计器 配的 templateCode /
   * channelCode 等静态字段由此流入 worker，无需每次触发时手工重复。
   *
   * <p>ADR-009 Stage 3: node_params 中形如 {@code $.nodes.<X>.output.<key>} / {@code
   * $.workflowRun.<key>} 的引用,会先经 {@link WorkflowParamResolver} 用 {@code workflow_run} 上下文替换为
   * 实际值,再合并到 payload。
   */
  @SuppressWarnings("unchecked")
  private void mergeNodeParams(
      Map<String, Object> payload, WorkflowNodeEntity workflowNode, WorkflowRunEntity workflowRun) {
    if (workflowNode == null || workflowNode.getNodeParams() == null) {
      return;
    }
    String raw = workflowNode.getNodeParams();
    if (raw.isBlank()) {
      return;
    }
    try {
      Object parsed = JsonUtils.fromJson(raw, Object.class);
      if (parsed instanceof Map<?, ?> map) {
        Object resolved = workflowParamResolver.resolve(map, loadWorkflowRunContext(workflowRun));
        if (resolved instanceof Map<?, ?> resolvedMap) {
          ((Map<String, Object>) resolvedMap).forEach(payload::putIfAbsent);
        }
      }
    } catch (IllegalArgumentException ignored) {
      // node_params 非 Map 或畸形——静默跳过，不让坏数据阻断派发
    }
  }

  /**
   * ADR-009 Stage 3: 加载 workflow_run 内所有已完成节点的 output → 构造 {@link WorkflowRunContext}。 派发前调用,不持久化;
   * 仅为本次派发的 node_params DSL 解析提供 {@code $.nodes.<X>.output.<key>} 数据源。
   *
   * <p>workflowRun 为 null 时返回空 context(老路径不走 workflow,resolver 见到 nodes/workflowRun 引用会 fail-fast,
   * 但实际只有 workflow 派发路径会调到 mergeNodeParams,所以这里 null-safe 仅作防御)。
   */
  private WorkflowRunContext loadWorkflowRunContext(WorkflowRunEntity workflowRun) {
    if (workflowRun == null) {
      return new WorkflowRunContext() {
        @Override
        public boolean hasNode(String nodeCode) {
          return false;
        }

        @Override
        public Map<String, Object> nodeOutput(String nodeCode) {
          return null;
        }

        @Override
        public Map<String, Object> workflowRunFields() {
          return Map.of();
        }
      };
    }
    List<WorkflowNodeRunEntity> nodeRuns =
        workflowMappers.workflowNodeRunMapper.selectByWorkflowRunId(workflowRun.getId());
    Map<String, Map<String, Object>> nodeOutputs = new LinkedHashMap<>();
    for (WorkflowNodeRunEntity run : nodeRuns) {
      String code = run.getNodeCode();
      // 同 nodeCode 多次执行(retry / 循环)取最新一次的 output;selectByWorkflowRunId 按 run_seq asc 返回,
      // 后续覆盖前面 → 自然结果 = 最新 run_seq 的 output。
      Map<String, Object> parsedOutput = parseOutputJson(run.getOutput());
      nodeOutputs.put(code, parsedOutput);
    }
    Map<String, Object> workflowRunFields = new LinkedHashMap<>();
    if (workflowRun.getBizDate() != null) {
      workflowRunFields.put("bizDate", workflowRun.getBizDate().toString());
    }
    if (workflowRun.getTraceId() != null) {
      workflowRunFields.put("traceId", workflowRun.getTraceId());
    }
    return new WorkflowRunContext() {
      @Override
      public boolean hasNode(String nodeCode) {
        return nodeOutputs.containsKey(nodeCode);
      }

      @Override
      public Map<String, Object> nodeOutput(String nodeCode) {
        return nodeOutputs.get(nodeCode);
      }

      @Override
      public Map<String, Object> workflowRunFields() {
        return workflowRunFields;
      }
    };
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> parseOutputJson(String outputJson) {
    if (outputJson == null || outputJson.isBlank()) {
      return null;
    }
    try {
      Object parsed = JsonUtils.fromJson(outputJson, Object.class);
      if (parsed instanceof Map<?, ?> map) {
        return new LinkedHashMap<>((Map<String, Object>) map);
      }
    } catch (IllegalArgumentException ignored) {
      // 数据库里 output 列异常,不影响派发,按"无产出"语义返回 null
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> parsePayloadMap(String payloadJson) {
    if (payloadJson == null || payloadJson.isBlank()) {
      return Map.of();
    }
    try {
      Object payloadObject = JsonUtils.fromJson(payloadJson, Object.class);
      if (payloadObject instanceof Map<?, ?> payloadMap) {
        return new LinkedHashMap<>((Map<String, Object>) payloadMap);
      }
    } catch (IllegalArgumentException exception) {
      return Map.of();
    }
    return Map.of();
  }
}
