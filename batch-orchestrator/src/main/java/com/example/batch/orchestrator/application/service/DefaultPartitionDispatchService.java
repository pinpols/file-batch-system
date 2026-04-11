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
import com.example.batch.orchestrator.mapper.MarkInstanceRunningParam;
import com.example.batch.orchestrator.mapper.WorkflowRunMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Launch 的 T2 阶段：把“准备态的 job_instance/workflow_run”推进到“可执行运行态”。
 *
 * <p>核心职责：
 *
 * <ul>
 *   <li>根据 DAG 初始节点或调度计划（SchedulePlan）创建 {@code job_partition}
 *   <li>为每个 partition 创建 {@code job_task}（并同步创建 step 镜像 {@code job_step_instance}）
 *   <li>对可派发的任务统一写入 outbox（由 outbox forwarder 负责投递到 Kafka）
 *   <li>根据资源调度决策把 instance 标记为 RUNNING 或 WAITING
 * </ul>
 */
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

    private record TaskExecutionContext(
            LaunchRequest request,
            Map<String, Object> effectiveParams,
            String traceId,
            JobInstanceEntity jobInstance) {}

    private record TaskSchedulingContext(
            SchedulePlan plan,
            List<JobPartitionEntity> partitions,
            ResourceSchedulingDecision decision) {}

    private record TaskCreationContext(
            TaskExecutionContext execution, TaskSchedulingContext scheduling) {
        private TaskBuildContext buildContext(JobPartitionEntity partition) {
            return new TaskBuildContext(this, partition);
        }
    }

    private record TaskBuildContext(TaskCreationContext creation, JobPartitionEntity partition) {}

    @Override
    @Transactional
    public void dispatch(DispatchContext context) {
        LaunchRequest request = context.request();
        Map<String, Object> effectiveParams = context.effectiveParams();
        String traceId = context.traceId();
        JobInstanceEntity jobInstance = context.jobInstance();
        WorkflowRunEntity workflowRun = context.workflowRun();
        List<WorkflowDagService.DagNodeResolution> initialNodes = context.initialNodes();
        Instant startedAt = context.startedAt();
        // 说明：如果有 DAG 初始节点（非 START），优先走 DAG dispatch；否则走普通计划调度（schedulePlan + resourceScheduler）。
        boolean dispatchable = true;
        int partitionCount;
        String sourcePayload = buildPayloadJson(effectiveParams);
        if (initialNodes != null && !initialNodes.isEmpty()) {
            partitionCount = 0;
            for (WorkflowDagService.DagNodeResolution initialNode : initialNodes) {
                if (initialNode == null
                        || WorkflowNodeCode.START.code().equals(initialNode.nodeCode())) {
                    continue;
                }
                partitionCount +=
                        workflowNodeDispatchService.dispatchNode(
                                jobInstance, workflowRun, initialNode, sourcePayload, traceId);
            }
        } else {
            SchedulePlan plan =
                    schedulePlanBuilder.build(
                            new SchedulePlanCommand(
                                    request.tenantId(),
                                    request.jobCode(),
                                    request.bizDate().toString(),
                                    effectiveParams));
            ResourceSchedulingDecision decision =
                    resourceScheduler.schedule(buildSchedulingRequest(plan));
            if (decision.isFailFast()) {
                throw new BizException(ResultCode.BUSINESS_ERROR, decision.getReasonMessage());
            }
            applySchedulingDecision(plan, decision);
            List<JobPartitionEntity> partitions =
                    partitionLifecycleService.createPartitions(
                            plan, jobInstance.getId(), decision.getPartitionStatus());
            createTasksAndMaybeOutboxEvents(
                    new TaskCreationContext(
                            new TaskExecutionContext(
                                    request, effectiveParams, traceId, jobInstance),
                            new TaskSchedulingContext(plan, partitions, decision)));
            partitionCount = partitions.size();
            dispatchable = decision.isDispatchable();
        }
        // 内联调用 markLaunchRuntime
        if (dispatchable) {
            // 可派发：推进为 RUNNING，并记录 startedAt；任务派发由 outbox 驱动，避免直接 send Kafka 导致事务边界混乱。
            int updated =
                    jobInstanceMapper.markRunning(
                            MarkInstanceRunningParam.builder()
                                    .tenantId(jobInstance.getTenantId())
                                    .id(jobInstance.getId())
                                    .instanceStatus(
                                            stateMachine.transition(jobInstance, "START").toState())
                                    .expectedPartitionCount(partitionCount)
                                    .startedAt(startedAt)
                                    .expectedVersion(jobInstance.getVersion())
                                    .build());
            if (updated <= 0) {
                throw new BizException(
                        ResultCode.STATE_CONFLICT, "job instance launch transition conflict");
            }
            jobInstance.setVersion(
                    (jobInstance.getVersion() == null ? 0L : jobInstance.getVersion()) + 1);
            workflowRunMapper.markRunning(
                    jobInstance.getTenantId(),
                    workflowRun.getId(),
                    stateMachine.transition(workflowRun, "START").toState(),
                    workflowRun.getCurrentNodeCode(),
                    startedAt);
        } else {
            // 不可派发（资源不足/窗口限制等）：instance 进入 WAITING，由等待派发调度器后续推进。
            int updated =
                    jobInstanceMapper.markRunning(
                            MarkInstanceRunningParam.builder()
                                    .tenantId(jobInstance.getTenantId())
                                    .id(jobInstance.getId())
                                    .instanceStatus(JobInstanceStatus.WAITING.code())
                                    .expectedPartitionCount(partitionCount)
                                    .startedAt(null)
                                    .expectedVersion(jobInstance.getVersion())
                                    .build());
            if (updated <= 0) {
                throw new BizException(
                        ResultCode.STATE_CONFLICT, "job instance waiting transition conflict");
            }
            jobInstance.setVersion(
                    (jobInstance.getVersion() == null ? 0L : jobInstance.getVersion()) + 1);
            workflowRunMapper.markRunning(
                    jobInstance.getTenantId(),
                    workflowRun.getId(),
                    WorkflowRunStatus.CREATED.code(),
                    workflowRun.getCurrentNodeCode(),
                    null);
        }
    }

    private void createTasksAndMaybeOutboxEvents(TaskCreationContext context) {
        if (context.scheduling().partitions().isEmpty()) {
            return;
        }
        for (JobPartitionEntity partition : context.scheduling().partitions()) {
            JobTaskEntity task = buildTask(context.buildContext(partition));
            taskExecutionService.createTask(task);
            if (context.scheduling().decision().isDispatchable()
                    && partitionLifecycleService.releaseForDispatch(
                            partition,
                            task,
                            PartitionStatus.CREATED.code(),
                            TaskStatus.CREATED.code())) {
                taskDispatchOutboxService.writeDispatchEvent(
                        context.execution().jobInstance(),
                        task,
                        partition,
                        context.execution().traceId(),
                        context.execution().request().tenantId() + ":" + task.getId());
            }
        }
    }

    private JobTaskEntity buildTask(TaskBuildContext context) {
        JobTaskEntity task = new JobTaskEntity();
        task.setTenantId(context.creation().execution().request().tenantId());
        task.setJobInstanceId(context.creation().execution().jobInstance().getId());
        task.setJobPartitionId(context.partition().getId());
        task.setTaskType(
                resolveTaskType(context.creation().scheduling().plan(), context.partition()));
        task.setTaskSeq(1);
        task.setAssignedWorkerCode(
                resolveSelectedWorkerId(
                        context.creation().scheduling().plan(), context.partition()));
        ResourceSchedulingDecision decision = context.creation().scheduling().decision();
        task.setTaskStatus(
                decision == null || decision.getTaskStatus() == null
                        ? TaskStatus.READY.code()
                        : decision.getTaskStatus());
        task.setVersion(0L);
        task.setTaskPayload(buildPayloadJson(context.creation().execution().effectiveParams()));
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

    private String resolveTaskType(SchedulePlan plan, JobPartitionEntity partition) {
        if (partition != null
                && partition.getWorkerGroup() != null
                && plan != null
                && plan.getDefaultWorkerRoute() != null
                && plan.getDefaultWorkerRoute().getWorkerType() != null) {
            return plan.getDefaultWorkerRoute().getWorkerType();
        }
        return plan == null ? null : plan.getDefaultWorkerType();
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

    private String buildPayloadJson(Map<String, Object> params) {
        Map<String, Object> payload = params == null ? Map.of() : params;
        return JsonUtils.toJson(payload);
    }
}
