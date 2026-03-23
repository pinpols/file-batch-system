package com.example.batch.orchestrator.application.service;

import com.example.batch.common.utils.JsonUtils;
import com.example.batch.common.enums.PartitionStatus;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.orchestrator.application.engine.TaskDispatchOutboxService;
import com.example.batch.orchestrator.application.plan.SchedulePlan;
import com.example.batch.orchestrator.application.plan.SchedulePlanBuilder;
import com.example.batch.orchestrator.application.plan.SchedulePlanCommand;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowRunEntity;
import com.example.batch.orchestrator.domain.query.JobPartitionQuery;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import com.example.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import com.example.batch.orchestrator.mapper.WorkflowNodeMapper;
import com.example.batch.orchestrator.application.scheduler.ResourceScheduler;
import com.example.batch.orchestrator.domain.scheduler.ResourceSchedulingDecision;
import com.example.batch.orchestrator.domain.scheduler.ResourceSchedulingRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DefaultWorkflowNodeDispatchService implements WorkflowNodeDispatchService {

    private final WorkflowNodeMapper workflowNodeMapper;
    private final SchedulePlanBuilder schedulePlanBuilder;
    private final PartitionLifecycleService partitionLifecycleService;
    private final TaskDispatchOutboxService taskDispatchOutboxService;
    private final JobInstanceMapper jobInstanceMapper;
    private final JobPartitionMapper jobPartitionMapper;
    private final WorkflowNodeRunMapper workflowNodeRunMapper;
    private final WorkflowDagService workflowDagService;
    private final ResourceScheduler resourceScheduler;
    private final ObjectProvider<TaskExecutionService> taskExecutionServiceProvider;

    @Override
    @Transactional
    public int dispatchNode(JobInstanceEntity jobInstance,
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
                sourcePayload
        )) {
            return 0;
        }
        WorkflowNodeEntity workflowNode = workflowNodeMapper.selectByWorkflowDefinitionIdAndNodeCode(
                workflowRun.getWorkflowDefinitionId(),
                node.nodeCode()
        );
        if (workflowNode == null) {
            return 0;
        }
        if (isNodeAlreadyActivated(workflowRun.getId(), node.nodeCode())) {
            return 0;
        }
        if (isGatewayNode(workflowNode.getNodeType())) {
            return dispatchGatewayNode(jobInstance, workflowRun, node, sourcePayload);
        }
        recordNodeRunReady(workflowRun.getId(), node.nodeCode(), node.nodeType());
        String targetJobCode = workflowNode.getRelatedJobCode() == null || workflowNode.getRelatedJobCode().isBlank()
                ? jobInstance.getJobCode()
                : workflowNode.getRelatedJobCode();
        SchedulePlan plan = schedulePlanBuilder.build(new SchedulePlanCommand(
                jobInstance.getTenantId(),
                targetJobCode,
                jobInstance.getBizDate().toString(),
                parsePayloadMap(sourcePayload)
        ));
        if (plan == null || plan.getPartitions() == null || plan.getPartitions().isEmpty()) {
            return 0;
        }
        plan.setWindowCode(workflowNode.getWindowCode() == null || workflowNode.getWindowCode().isBlank()
                ? plan.getWindowCode()
                : workflowNode.getWindowCode());
        if (workflowNode.getWorkerGroup() != null && !workflowNode.getWorkerGroup().isBlank()) {
            plan.setWorkerGroup(workflowNode.getWorkerGroup());
        }
        ResourceSchedulingDecision decision = resourceScheduler.schedule(buildSchedulingRequest(plan));
        applySchedulingDecision(plan, decision);
        List<JobPartitionEntity> existingPartitions = jobPartitionMapper.selectByQuery(new JobPartitionQuery(
                jobInstance.getTenantId(),
                jobInstance.getId(),
                null,
                null
        ));
        int nextPartitionNo = existingPartitions.stream()
                .map(JobPartitionEntity::getPartitionNo)
                .filter(java.util.Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0) + 1;
        for (SchedulePlan.PartitionPlan partitionPlan : plan.getPartitions()) {
            partitionPlan.setPartitionNo(nextPartitionNo++);
            partitionPlan.setPartitionKey(partitionPlan.getPartitionKey() + ":" + node.nodeCode() + ":" + partitionPlan.getPartitionNo());
            partitionPlan.setBusinessKey((partitionPlan.getBusinessKey() == null ? targetJobCode : partitionPlan.getBusinessKey())
                    + ":" + node.nodeCode());
        }
        List<JobPartitionEntity> newPartitions = partitionLifecycleService.createPartitions(
                plan,
                jobInstance.getId(),
                decision.getPartitionStatus()
        );
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
            task.setTaskPayload(taskPayload);
            taskExecutionServiceProvider.getObject().createTask(task);
            if (decision.isDispatchable() && partitionLifecycleService.releaseForDispatch(
                    partition,
                    task,
                    PartitionStatus.CREATED.code(),
                    TaskStatus.CREATED.code()
            )) {
                taskDispatchOutboxService.writeDispatchEvent(
                        jobInstance,
                        task,
                        partition,
                        traceId,
                        jobInstance.getTenantId() + ":workflow:" + workflowRun.getId() + ":" + node.nodeCode() + ":" + task.getId()
                );
            }
        }
        int currentExpectedPartitionCount = jobInstance.getExpectedPartitionCount() == null ? 0 : jobInstance.getExpectedPartitionCount();
        int updated = jobInstanceMapper.updateExpectedPartitionCount(
                jobInstance.getTenantId(),
                jobInstance.getId(),
                currentExpectedPartitionCount + newPartitions.size(),
                jobInstance.getVersion()
        );
        if (updated <= 0) {
            throw new IllegalStateException("job instance expected partition count update conflict");
        }
        jobInstance.setExpectedPartitionCount(currentExpectedPartitionCount + newPartitions.size());
        jobInstance.setVersion((jobInstance.getVersion() == null ? 0L : jobInstance.getVersion()) + 1);
        return newPartitions.size();
    }

    private int dispatchGatewayNode(JobInstanceEntity jobInstance,
                                    WorkflowRunEntity workflowRun,
                                    WorkflowDagService.DagNodeResolution node,
                                    String sourcePayload) {
        Instant now = Instant.now();
        WorkflowNodeRunEntity runningNode = new WorkflowNodeRunEntity();
        runningNode.setWorkflowRunId(workflowRun.getId());
        runningNode.setNodeCode(node.nodeCode());
        runningNode.setNodeType(node.nodeType());
        runningNode.setRunSeq(nextRunSeq(workflowRun.getId(), node.nodeCode()));
        runningNode.setNodeStatus(com.example.batch.common.enums.WorkflowNodeRunStatus.RUNNING.code());
        runningNode.setRetryCount(0);
        runningNode.setStartedAt(now);
        runningNode.setDurationMs(0L);
        workflowNodeRunMapper.insert(runningNode);
        workflowNodeRunMapper.updateStatus(
                runningNode.getId(),
                com.example.batch.common.enums.WorkflowNodeRunStatus.SUCCESS.code(),
                null,
                null,
                0L,
                now
        );
        List<WorkflowDagService.DagNodeResolution> nextNodes = workflowDagService.resolveNextNodes(
                workflowRun.getWorkflowDefinitionId(),
                node.nodeCode(),
                true,
                sourcePayload
        );
        int dispatchedCount = 0;
        for (WorkflowDagService.DagNodeResolution nextNode : nextNodes) {
            if (com.example.batch.common.enums.WorkflowNodeCode.END.code().equals(nextNode.nodeCode())) {
                createTerminalNodeRun(workflowRun.getId(), nextNode, now);
                continue;
            }
            dispatchedCount += dispatchNode(jobInstance, workflowRun, nextNode, sourcePayload, jobInstance.getTraceId());
        }
        return dispatchedCount;
    }

    private void createTerminalNodeRun(Long workflowRunId,
                                       WorkflowDagService.DagNodeResolution nextNode,
                                       Instant finishedAt) {
        WorkflowNodeRunEntity terminalNode = new WorkflowNodeRunEntity();
        terminalNode.setWorkflowRunId(workflowRunId);
        terminalNode.setNodeCode(nextNode.nodeCode());
        terminalNode.setNodeType(nextNode.nodeType());
        terminalNode.setRunSeq(nextRunSeq(workflowRunId, nextNode.nodeCode()));
        terminalNode.setNodeStatus(com.example.batch.common.enums.WorkflowNodeRunStatus.SUCCESS.code());
        terminalNode.setRetryCount(0);
        terminalNode.setStartedAt(finishedAt);
        terminalNode.setFinishedAt(finishedAt);
        terminalNode.setDurationMs(0L);
        workflowNodeRunMapper.insert(terminalNode);
    }

    private boolean isGatewayNode(String nodeType) {
        return com.example.batch.common.enums.WorkflowNodeType.GATEWAY.code().equalsIgnoreCase(nodeType)
                || com.example.batch.common.enums.WorkflowNodeType.START.code().equalsIgnoreCase(nodeType);
    }

    private boolean isNodeAlreadyActivated(Long workflowRunId, String nodeCode) {
        WorkflowNodeRunEntity latestNodeRun = workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(workflowRunId, nodeCode);
        if (latestNodeRun == null) {
            return false;
        }
        String nodeStatus = latestNodeRun.getNodeStatus();
        return com.example.batch.common.enums.WorkflowNodeRunStatus.READY.code().equals(nodeStatus)
                || com.example.batch.common.enums.WorkflowNodeRunStatus.RUNNING.code().equals(nodeStatus)
                || com.example.batch.common.enums.WorkflowNodeRunStatus.SUCCESS.code().equals(nodeStatus);
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
        request.setRequestedPartitionCount(plan.getPartitionCount() == null ? 1 : plan.getPartitionCount());
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
        if (partition != null && partition.getWorkerCode() != null && !partition.getWorkerCode().isBlank()) {
            return partition.getWorkerCode();
        }
        if (plan != null && plan.getDefaultWorkerRoute() != null) {
            return plan.getDefaultWorkerRoute().getWorkerId();
        }
        return null;
    }

    private void recordNodeRunReady(Long workflowRunId, String nodeCode, String nodeType) {
        WorkflowNodeRunEntity readyNode = new WorkflowNodeRunEntity();
        readyNode.setWorkflowRunId(workflowRunId);
        readyNode.setNodeCode(nodeCode);
        readyNode.setNodeType(nodeType);
        readyNode.setRunSeq(nextRunSeq(workflowRunId, nodeCode));
        readyNode.setNodeStatus(com.example.batch.common.enums.WorkflowNodeRunStatus.READY.code());
        readyNode.setRetryCount(0);
        readyNode.setDurationMs(0L);
        workflowNodeRunMapper.insert(readyNode);
    }

    private int nextRunSeq(Long workflowRunId, String nodeCode) {
        WorkflowNodeRunEntity latestNodeRun = workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(workflowRunId, nodeCode);
        return latestNodeRun == null || latestNodeRun.getRunSeq() == null ? 1 : latestNodeRun.getRunSeq() + 1;
    }

    @SuppressWarnings("unchecked")
    private String buildTaskPayload(String sourcePayload,
                                    WorkflowDagService.DagNodeResolution node,
                                    String targetJobCode) {
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
