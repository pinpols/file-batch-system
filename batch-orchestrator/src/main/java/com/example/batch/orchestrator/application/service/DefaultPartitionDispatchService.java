package com.example.batch.orchestrator.application.service;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.JobInstanceStatus;
import com.example.batch.common.enums.PartitionStatus;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.enums.WorkflowNodeCode;
import com.example.batch.common.enums.WorkflowRunStatus;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.persistence.entity.WorkflowRunEntity;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.application.engine.TaskDispatchOutboxService;
import com.example.batch.orchestrator.application.plan.SchedulePlan;
import com.example.batch.orchestrator.application.plan.SchedulePlanBuilder;
import com.example.batch.orchestrator.application.plan.SchedulePlanCommand;
import com.example.batch.orchestrator.application.scheduler.ResourceScheduler;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.scheduler.ResourceSchedulingDecision;
import com.example.batch.orchestrator.domain.scheduler.ResourceSchedulingRequest;
import com.example.batch.orchestrator.domain.statemachine.StateMachine;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.WorkflowRunMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class DefaultPartitionDispatchService implements PartitionDispatchService {

    private final SchedulePlanBuilder schedulePlanBuilder;
    private final ResourceScheduler resourceScheduler;
    private final PartitionLifecycleService partitionLifecycleService;
    private final TaskExecutionService taskExecutionService;
    private final TaskDispatchOutboxService taskDispatchOutboxService;
    private final StateMachine<Object> stateMachine;
    private final WorkflowNodeDispatchService workflowNodeDispatchService;
    private final JobInstanceMapper jobInstanceMapper;
    private final WorkflowRunMapper workflowRunMapper;

    @Override
    @Transactional
    public void dispatch(LaunchRequest request,
                         Map<String, Object> effectiveParams,
                         String traceId,
                         JobInstanceEntity jobInstance,
                         WorkflowRunEntity workflowRun,
                         List<WorkflowDagService.DagNodeResolution> initialNodes,
                         Instant startedAt) {
        boolean dispatchable = true;
        int partitionCount;
        String sourcePayload = buildPayloadJson(effectiveParams);
        if (initialNodes != null && !initialNodes.isEmpty()) {
            partitionCount = 0;
            for (WorkflowDagService.DagNodeResolution initialNode : initialNodes) {
                if (initialNode == null || WorkflowNodeCode.START.code().equals(initialNode.nodeCode())) {
                    continue;
                }
                partitionCount += workflowNodeDispatchService.dispatchNode(
                        jobInstance, workflowRun, initialNode, sourcePayload, traceId);
            }
        } else {
            SchedulePlan plan = schedulePlanBuilder.build(new SchedulePlanCommand(
                    request.tenantId(), request.jobCode(), request.bizDate().toString(), effectiveParams));
            ResourceSchedulingDecision decision = resourceScheduler.schedule(buildSchedulingRequest(plan));
            if (decision.isFailFast()) {
                throw new BizException(ResultCode.INVALID_ARGUMENT, decision.getReasonMessage());
            }
            applySchedulingDecision(plan, decision);
            List<JobPartitionEntity> partitions = partitionLifecycleService.createPartitions(
                    plan, jobInstance.getId(), decision.getPartitionStatus());
            createTasksAndMaybeOutboxEvents(request, effectiveParams, traceId, jobInstance, plan, partitions, decision);
            partitionCount = partitions.size();
            dispatchable = decision.isDispatchable();
        }
        // markLaunchRuntime inline
        if (dispatchable) {
            int updated = jobInstanceMapper.markRunning(
                    jobInstance.getTenantId(), jobInstance.getId(),
                    stateMachine.transition(jobInstance, "START").toState(),
                    partitionCount, startedAt, jobInstance.getVersion());
            if (updated <= 0) {
                throw new BizException(ResultCode.STATE_CONFLICT, "job instance launch transition conflict");
            }
            jobInstance.setVersion((jobInstance.getVersion() == null ? 0L : jobInstance.getVersion()) + 1);
            workflowRunMapper.markRunning(
                    jobInstance.getTenantId(), workflowRun.getId(),
                    stateMachine.transition(workflowRun, "START").toState(),
                    workflowRun.getCurrentNodeCode(), startedAt);
        } else {
            int updated = jobInstanceMapper.markRunning(
                    jobInstance.getTenantId(), jobInstance.getId(),
                    JobInstanceStatus.WAITING.code(),
                    partitionCount, null, jobInstance.getVersion());
            if (updated <= 0) {
                throw new BizException(ResultCode.STATE_CONFLICT, "job instance waiting transition conflict");
            }
            jobInstance.setVersion((jobInstance.getVersion() == null ? 0L : jobInstance.getVersion()) + 1);
            workflowRunMapper.markRunning(
                    jobInstance.getTenantId(), workflowRun.getId(),
                    WorkflowRunStatus.CREATED.code(),
                    workflowRun.getCurrentNodeCode(), null);
        }
    }

    private void createTasksAndMaybeOutboxEvents(LaunchRequest request,
                                                 Map<String, Object> effectiveParams,
                                                 String traceId,
                                                 JobInstanceEntity jobInstance,
                                                 SchedulePlan plan,
                                                 List<JobPartitionEntity> partitions,
                                                 ResourceSchedulingDecision decision) {
        if (partitions.isEmpty()) {
            return;
        }
        for (JobPartitionEntity partition : partitions) {
            JobTaskEntity task = buildTask(request, jobInstance, plan, partition, decision, effectiveParams);
            taskExecutionService.createTask(task);
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
                        request.tenantId() + ":" + task.getId()
                );
            }
        }
    }

    private JobTaskEntity buildTask(LaunchRequest request,
                                    JobInstanceEntity jobInstance,
                                    SchedulePlan plan,
                                    JobPartitionEntity partition,
                                    ResourceSchedulingDecision decision,
                                    Map<String, Object> effectiveParams) {
        JobTaskEntity task = new JobTaskEntity();
        task.setTenantId(request.tenantId());
        task.setJobInstanceId(jobInstance.getId());
        task.setJobPartitionId(partition.getId());
        task.setTaskType(resolveTaskType(plan, partition));
        task.setTaskSeq(1);
        task.setAssignedWorkerCode(resolveSelectedWorkerId(plan, partition));
        task.setTaskStatus(decision == null || decision.getTaskStatus() == null
                ? TaskStatus.READY.code()
                : decision.getTaskStatus());
        task.setTaskPayload(buildPayloadJson(effectiveParams));
        return task;
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

    private String resolveTaskType(SchedulePlan plan, JobPartitionEntity partition) {
        if (partition != null && partition.getWorkerGroup() != null && plan != null && plan.getDefaultWorkerRoute() != null
                && plan.getDefaultWorkerRoute().getWorkerType() != null) {
            return plan.getDefaultWorkerRoute().getWorkerType();
        }
        return plan == null ? null : plan.getDefaultWorkerType();
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

    private String buildPayloadJson(Map<String, Object> params) {
        Map<String, Object> payload = params == null ? Map.of() : params;
        return JsonUtils.toJson(payload);
    }
}
