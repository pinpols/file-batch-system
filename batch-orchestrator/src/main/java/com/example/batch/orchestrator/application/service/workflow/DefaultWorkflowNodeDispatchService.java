package com.example.batch.orchestrator.application.service.workflow;

import com.example.batch.common.enums.PartitionStatus;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.enums.WorkflowNodeCode;
import com.example.batch.common.enums.WorkflowNodeRunStatus;
import com.example.batch.common.enums.WorkflowNodeType;
import com.example.batch.common.persistence.entity.WorkflowRunEntity;
import com.example.batch.orchestrator.application.engine.OutboxEventKeyGenerator;
import com.example.batch.orchestrator.application.engine.TaskDispatchOutboxService;
import com.example.batch.orchestrator.application.plan.SchedulePlan;
import com.example.batch.orchestrator.application.plan.SchedulePlanBuilder;
import com.example.batch.orchestrator.application.plan.SchedulePlanCommand;
import com.example.batch.orchestrator.application.scheduler.ResourceScheduler;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import com.example.batch.orchestrator.domain.param.UpdateNodeRunStatusParam;
import com.example.batch.orchestrator.domain.query.JobPartitionQuery;
import com.example.batch.orchestrator.application.service.task.ChildJobLaunchSupport;
import com.example.batch.orchestrator.application.service.task.OrchestratorJobMappers;
import com.example.batch.orchestrator.application.service.task.PartitionLifecycleService;
import com.example.batch.orchestrator.application.service.task.TaskExecutionService;
import com.example.batch.orchestrator.domain.scheduling.ResourceSchedulingDecision;
import com.example.batch.orchestrator.domain.scheduling.ResourceSchedulingRequest;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
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
      return childJobLaunchSupport.dispatchJobNode(
          jobInstance, workflowRun, node, workflowNode, sourcePayload, traceId);
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
                WorkflowNodePayloadBuilder.parsePayloadMap(sourcePayload)));
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
        payloadBuilder.buildTaskPayload(
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
}
