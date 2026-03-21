package com.example.batch.orchestrator.service;

import com.example.batch.common.constants.BatchStatusConstants;
import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.enums.JobInstanceStatus;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.enums.WorkflowNodeCode;
import com.example.batch.common.enums.WorkflowNodeRunStatus;
import com.example.batch.common.enums.WorkflowRunStatus;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.IdGenerator;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.application.engine.TaskDispatchOutboxService;
import com.example.batch.orchestrator.application.plan.SchedulePlan;
import com.example.batch.orchestrator.application.plan.SchedulePlanBuilder;
import com.example.batch.orchestrator.application.plan.SchedulePlanCommand;
import com.example.batch.orchestrator.application.service.PartitionLifecycleService;
import com.example.batch.orchestrator.application.service.TaskExecutionService;
import com.example.batch.orchestrator.application.service.WorkflowDagService;
import com.example.batch.orchestrator.application.service.WorkflowNodeDispatchService;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.entity.JobDefinitionRecord;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import com.example.batch.orchestrator.domain.entity.TriggerRequestEntity;
import com.example.batch.orchestrator.mapper.TriggerRequestMapper;
import com.example.batch.orchestrator.domain.entity.WorkflowRunEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowDefinitionRecord;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import com.example.batch.orchestrator.domain.statemachine.StateMachine;
import com.example.batch.orchestrator.mapper.WorkflowRunMapper;
import com.example.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import com.example.batch.orchestrator.scheduler.ResourceScheduler;
import com.example.batch.orchestrator.scheduler.ResourceSchedulingDecision;
import com.example.batch.orchestrator.scheduler.ResourceSchedulingRequest;
import com.example.batch.orchestrator.repository.JobDefinitionRepository;
import com.example.batch.orchestrator.repository.WorkflowDefinitionRepository;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DefaultLaunchService implements LaunchService {

    private final TriggerRequestMapper triggerRequestMapper;
    private final JobDefinitionRepository jobDefinitionRepository;
    private final WorkflowDefinitionRepository workflowDefinitionRepository;
    private final JobInstanceMapper jobInstanceMapper;
    private final JobPartitionMapper jobPartitionMapper;
    private final JobTaskMapper jobTaskMapper;
    private final WorkflowRunMapper workflowRunMapper;
    private final WorkflowNodeRunMapper workflowNodeRunMapper;
    private final SchedulePlanBuilder schedulePlanBuilder;
    private final PartitionLifecycleService partitionLifecycleService;
    private final TaskExecutionService taskExecutionService;
    private final TaskDispatchOutboxService taskDispatchOutboxService;
    private final StateMachine<Object> stateMachine;
    private final WorkflowDagService workflowDagService;
    private final WorkflowNodeDispatchService workflowNodeDispatchService;
    private final ResourceScheduler resourceScheduler;

    @Override
    @Transactional
    public LaunchResponse launch(LaunchRequest request) {
        validate(request);
        TriggerRequestEntity triggerRequest = triggerRequestMapper.selectByTenantAndRequestId(request.tenantId(), request.requestId());
        if (triggerRequest == null) {
            throw new BizException(ResultCode.NOT_FOUND, "trigger request not found");
        }

        JobDefinitionRecord jobDefinition = jobDefinitionRepository.findFirstByTenantIdAndJobCodeAndEnabled(request.tenantId(), request.jobCode(), true);
        if (jobDefinition == null) {
            triggerRequestMapper.updateAcceptance(request.tenantId(), request.requestId(), BatchStatusConstants.REJECTED, null);
            throw new BizException(ResultCode.NOT_FOUND, "job definition not found");
        }

        WorkflowDefinitionRecord workflowDefinition = workflowDefinitionRepository
                .findFirstByTenantIdAndWorkflowCodeAndEnabled(request.tenantId(), request.jobCode(), true);
        if (workflowDefinition == null) {
            triggerRequestMapper.updateAcceptance(request.tenantId(), request.requestId(), BatchStatusConstants.REJECTED, null);
            throw new BizException(ResultCode.NOT_FOUND, "workflow definition not found for job code");
        }

        JobInstanceEntity existing = jobInstanceMapper.selectByTenantAndDedupKey(request.tenantId(), triggerRequest.getDedupKey());
        if (existing != null) {
            triggerRequestMapper.updateAcceptance(request.tenantId(), request.requestId(), BatchStatusConstants.DUPLICATE, existing.getId());
            return new LaunchResponse(existing.getInstanceNo(), existing.getTraceId());
        }

        String traceId = request.traceId() == null || request.traceId().isBlank() ? IdGenerator.newTraceId() : request.traceId();

        JobInstanceEntity jobInstance = new JobInstanceEntity();
        jobInstance.setTenantId(request.tenantId());
        jobInstance.setJobDefinitionId(jobDefinition.getId());
        jobInstance.setTriggerRequestId(triggerRequest.getId());
        jobInstance.setJobCode(request.jobCode());
        jobInstance.setInstanceNo(IdGenerator.newBusinessNo("inst"));
        jobInstance.setBizDate(request.bizDate());
        jobInstance.setTriggerType(request.triggerType().code());
        jobInstance.setInstanceStatus(JobInstanceStatus.CREATED.code());
        jobInstance.setQueueCode(jobDefinition.getQueueCode());
        jobInstance.setWorkerGroup(jobDefinition.getWorkerGroup());
        jobInstance.setPriority(jobDefinition.getPriority() == null ? 5 : jobDefinition.getPriority());
        jobInstance.setDedupKey(triggerRequest.getDedupKey());
        jobInstance.setVersion(0L);
        jobInstance.setExpectedPartitionCount(0);
        jobInstance.setSuccessPartitionCount(0);
        jobInstance.setFailedPartitionCount(0);
        jobInstance.setTraceId(traceId);
        jobInstanceMapper.insert(jobInstance);

        WorkflowRunEntity workflowRun = new WorkflowRunEntity();
        workflowRun.setTenantId(request.tenantId());
        workflowRun.setWorkflowDefinitionId(workflowDefinition.getId());
        workflowRun.setRelatedJobInstanceId(jobInstance.getId());
        workflowRun.setBizDate(request.bizDate());
        workflowRun.setRunStatus(WorkflowRunStatus.CREATED.code());
        List<WorkflowDagService.DagNodeResolution> initialNodes = workflowDagService.resolveInitialNodes(
                workflowDefinition.getId(),
                buildPayloadJson(request.params())
        );
        workflowRun.setCurrentNodeCode(resolveInitialCurrentNode(initialNodes));
        workflowRun.setTraceId(traceId);
        workflowRunMapper.insert(workflowRun);

        Instant startedAt = Instant.now();
        bootstrapWorkflowRuns(workflowRun.getId(), startedAt);
        initializeDispatchRuntime(request, traceId, jobInstance, workflowRun, initialNodes, startedAt);

        triggerRequestMapper.updateAcceptance(request.tenantId(), request.requestId(), BatchStatusConstants.LAUNCHED, jobInstance.getId());
        return new LaunchResponse(jobInstance.getInstanceNo(), traceId);
    }

    /**
     * Launch 只负责初始化运行态和任务分发，不把 Kafka 当状态事实来源。
     */
    private void initializeDispatchRuntime(LaunchRequest request,
                                           String traceId,
                                           JobInstanceEntity jobInstance,
                                           WorkflowRunEntity workflowRun,
                                           List<WorkflowDagService.DagNodeResolution> initialNodes,
                                           Instant startedAt) {
        boolean dispatchable = true;
        int partitionCount;
        String sourcePayload = buildPayloadJson(request.params());
        if (initialNodes != null && !initialNodes.isEmpty()) {
            partitionCount = 0;
            for (WorkflowDagService.DagNodeResolution initialNode : initialNodes) {
                if (initialNode == null || WorkflowNodeCode.START.code().equals(initialNode.nodeCode())) {
                    continue;
                }
                partitionCount += workflowNodeDispatchService.dispatchNode(
                        jobInstance,
                        workflowRun,
                        initialNode,
                        sourcePayload,
                        traceId
                );
            }
        } else {
            SchedulePlan plan = schedulePlanBuilder.build(new SchedulePlanCommand(
                    request.tenantId(),
                    request.jobCode(),
                    request.bizDate().toString(),
                    request.params()
            ));
            ResourceSchedulingDecision decision = resourceScheduler.schedule(buildSchedulingRequest(plan));
            if (decision.isFailFast()) {
                throw new BizException(ResultCode.INVALID_ARGUMENT, decision.getReasonMessage());
            }
            applySchedulingDecision(plan, decision);
            List<JobPartitionEntity> partitions = partitionLifecycleService.createPartitions(
                    plan,
                    jobInstance.getId(),
                    decision.getPartitionStatus()
            );
            createTasksAndMaybeOutboxEvents(request, traceId, jobInstance, plan, partitions, decision);
            partitionCount = partitions.size();
            dispatchable = decision.isDispatchable();
        }
        markLaunchRuntime(request.tenantId(), jobInstance, workflowRun, partitionCount, dispatchable, startedAt);
    }

    private void createTasksAndMaybeOutboxEvents(LaunchRequest request,
                                                 String traceId,
                                                 JobInstanceEntity jobInstance,
                                                 SchedulePlan plan,
                                                 List<JobPartitionEntity> partitions,
                                                 ResourceSchedulingDecision decision) {
        if (partitions.isEmpty()) {
            return;
        }
        for (JobPartitionEntity partition : partitions) {
            JobTaskEntity task = buildTask(request, jobInstance, plan, partition, decision);
            taskExecutionService.createTask(task);
            if (decision.isDispatchable() && releasePartitionForDispatch(partition, task)) {
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
                                    ResourceSchedulingDecision decision) {
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
        task.setTaskPayload(buildPayloadJson(request.params()));
        return task;
    }

    private void bootstrapWorkflowRuns(Long workflowRunId,
                                       Instant startedAt) {
        WorkflowNodeRunEntity startNodeRun = new WorkflowNodeRunEntity();
        startNodeRun.setWorkflowRunId(workflowRunId);
        startNodeRun.setNodeCode(WorkflowNodeCode.START.code());
        startNodeRun.setNodeType(com.example.batch.common.enums.WorkflowNodeType.START.code());
        startNodeRun.setRunSeq(1);
        startNodeRun.setNodeStatus(WorkflowNodeRunStatus.SUCCESS.code());
        startNodeRun.setRetryCount(0);
        startNodeRun.setDurationMs(0L);
        startNodeRun.setStartedAt(startedAt);
        startNodeRun.setFinishedAt(startedAt);
        workflowNodeRunMapper.insert(startNodeRun);
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

    private void markLaunchRuntime(String tenantId,
                                   JobInstanceEntity jobInstance,
                                   WorkflowRunEntity workflowRun,
                                   int partitionCount,
                                   boolean dispatchable,
                                   Instant startedAt) {
        if (dispatchable) {
            jobInstanceMapper.markRunning(
                    tenantId,
                    jobInstance.getId(),
                    stateMachine.transition(jobInstance, "START").toState(),
                    partitionCount,
                    startedAt
            );
            workflowRunMapper.markRunning(
                    tenantId,
                    workflowRun.getId(),
                    stateMachine.transition(workflowRun, "START").toState(),
                    workflowRun.getCurrentNodeCode(),
                    startedAt
            );
            return;
        }
        jobInstanceMapper.markRunning(
                tenantId,
                jobInstance.getId(),
                JobInstanceStatus.WAITING.code(),
                partitionCount,
                null
        );
        workflowRunMapper.markRunning(
                tenantId,
                workflowRun.getId(),
                WorkflowRunStatus.CREATED.code(),
                workflowRun.getCurrentNodeCode(),
                null
        );
    }

    private String buildPayloadJson(Map<String, Object> params) {
        Map<String, Object> payload = params == null ? Map.of() : params;
        return JsonUtils.toJson(payload);
    }

    private String resolveInitialCurrentNode(List<WorkflowDagService.DagNodeResolution> initialNodes) {
        if (initialNodes == null || initialNodes.isEmpty()) {
            return WorkflowNodeCode.START.code();
        }
        Set<String> activeNodes = new LinkedHashSet<>();
        for (WorkflowDagService.DagNodeResolution initialNode : initialNodes) {
            if (initialNode == null || WorkflowNodeCode.START.code().equals(initialNode.nodeCode())) {
                continue;
            }
            activeNodes.add(initialNode.nodeCode());
        }
        return activeNodes.isEmpty() ? WorkflowNodeCode.START.code() : String.join(",", activeNodes);
    }

    private boolean releasePartitionForDispatch(JobPartitionEntity partition, JobTaskEntity task) {
        if (partition == null || task == null) {
            return false;
        }
        if (jobPartitionMapper.promoteStatus(
                partition.getTenantId(),
                partition.getId(),
                com.example.batch.common.enums.PartitionStatus.CREATED.code(),
                com.example.batch.common.enums.PartitionStatus.READY.code()
        ) <= 0) {
            return false;
        }
        if (jobTaskMapper.promoteStatus(
                task.getTenantId(),
                task.getId(),
                TaskStatus.CREATED.code(),
                TaskStatus.READY.code()
        ) <= 0) {
            return false;
        }
        JobPartitionEntity readyPartition = jobPartitionMapper.selectById(partition.getTenantId(), partition.getId());
        JobTaskEntity readyTask = jobTaskMapper.selectById(task.getTenantId(), task.getId());
        if (readyPartition != null) {
            partition.setPartitionStatus(readyPartition.getPartitionStatus());
        }
        if (readyTask != null) {
            task.setTaskStatus(readyTask.getTaskStatus());
        }
        return true;
    }

    private void validate(LaunchRequest request) {
        if (request == null) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "launch request is required");
        }
        if (request.tenantId() == null || request.tenantId().isBlank()) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "tenantId is required");
        }
        if (request.jobCode() == null || request.jobCode().isBlank()) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "jobCode is required");
        }
        if (request.bizDate() == null) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "bizDate is required");
        }
        if (request.triggerType() == null) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "triggerType is required");
        }
    }
}
