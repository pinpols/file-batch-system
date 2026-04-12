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
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    String taskPayload = buildTaskPayload(sourcePayload, node, targetJobCode);
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

    // 计算虚拟分区的 partition_no
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

    // 创建虚拟分区（RUNNING，无需 worker 领取）
    String idempotencyKey =
        jobInstance.getTenantId() + ":wf:" + workflowRun.getId() + ":" + node.nodeCode();
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

    // 构造虚拟 task 载荷，与 TASK 节点对齐，便于 outcome 服务解析 workflowNodeCode / workflowNodeType
    Map<String, Object> payloadMap = new LinkedHashMap<>(parsePayloadMap(sourcePayload));
    payloadMap.put("workflowNodeCode", node.nodeCode());
    payloadMap.put("workflowNodeType", node.nodeType());
    payloadMap.put("targetJobCode", refJobCode);
    String taskPayload = JsonUtils.toJson(payloadMap);

    // 创建虚拟 task（RUNNING），不产生 outbox 派发事件
    JobTaskEntity virtualTaskTemplate = new JobTaskEntity();
    virtualTaskTemplate.setTenantId(jobInstance.getTenantId());
    virtualTaskTemplate.setJobInstanceId(jobInstance.getId());
    virtualTaskTemplate.setJobPartitionId(virtualPartition.getId());
    virtualTaskTemplate.setTaskType("EXECUTION");
    virtualTaskTemplate.setTaskSeq(1);
    virtualTaskTemplate.setTaskStatus(TaskStatus.RUNNING.code());
    virtualTaskTemplate.setVersion(0L);
    virtualTaskTemplate.setTaskPayload(taskPayload);
    JobTaskEntity virtualTask =
        taskExecutionServiceProvider.getObject().createTask(virtualTaskTemplate);

    // 更新父作业期望分区数（乐观锁）
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
          "job instance expected partition count update conflict for JOB node " + node.nodeCode());
    }
    jobInstance.setExpectedPartitionCount(currentExpected + 1);
    jobInstance.setVersion((jobInstance.getVersion() == null ? 0L : jobInstance.getVersion()) + 1);

    // 为子作业写入 trigger_request，供 LaunchValidationService.load() 加载
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

    // 子作业启动参数含回指字段，便于子作业完成后回写本虚拟 task
    Map<String, Object> childParams = new LinkedHashMap<>(parsePayloadMap(sourcePayload));
    childParams.put("parentInstanceId", jobInstance.getId());
    childParams.put("_parentVirtualTaskId", virtualTask.getId());
    childParams.put("_parentWorkflowRunId", workflowRun.getId());
    childParams.put("_parentNodeCode", node.nodeCode());

    LaunchRequest childLaunchRequest =
        new LaunchRequest(
            jobInstance.getTenantId(),
            refJobCode,
            jobInstance.getBizDate(),
            TriggerType.EVENT,
            childRequestId,
            traceId,
            childParams);
    launchServiceProvider.getObject().launch(childLaunchRequest);

    return 1; // one virtual partition added to the parent job
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

  @SuppressWarnings("unchecked")
  private String buildTaskPayload(
      String sourcePayload, WorkflowDagService.DagNodeResolution node, String targetJobCode) {
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
    payload.put("workflowNodeCode", node.nodeCode());
    payload.put("workflowNodeType", node.nodeType());
    payload.put("targetJobCode", targetJobCode);
    return JsonUtils.toJson(payload);
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
