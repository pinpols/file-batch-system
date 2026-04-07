package com.example.batch.orchestrator.infrastructure.scheduler;

import com.example.batch.common.enums.JobInstanceStatus;
import com.example.batch.common.enums.PartitionStatus;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.enums.WorkflowRunStatus;
import com.example.batch.orchestrator.application.engine.TaskDispatchOutboxService;
import com.example.batch.orchestrator.application.ratelimit.RateLimitAction;
import com.example.batch.orchestrator.application.ratelimit.TenantActionRateLimiter;
import com.example.batch.orchestrator.application.scheduler.ResourceScheduler;
import com.example.batch.orchestrator.application.service.PartitionLifecycleService;
import com.example.batch.orchestrator.domain.entity.JobDefinitionRecord;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.common.persistence.entity.WorkflowRunEntity;
import com.example.batch.common.logging.BatchMdc;
import com.example.batch.common.logging.StructuredLogField;
import com.example.batch.orchestrator.domain.scheduler.ResourceSchedulingDecision;
import com.example.batch.orchestrator.domain.scheduler.ResourceSchedulingRequest;
import com.example.batch.orchestrator.mapper.MarkInstanceRunningParam;
import com.example.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import com.example.batch.orchestrator.application.service.OrchestratorJobMappers;
import com.example.batch.orchestrator.application.service.OrchestratorWorkflowMappers;
import com.example.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class WaitingPartitionDispatchScheduler {

    private final ResourceScheduler resourceScheduler;
    private final BatchOrchestratorGovernanceProperties governance;
    private final OrchestratorJobMappers jobMappers;
    private final OrchestratorWorkflowMappers workflowMappers;
    private final OrchestratorConfigCacheService configCacheService;
    private final TaskDispatchOutboxService taskDispatchOutboxService;
    private final PartitionLifecycleService partitionLifecycleService;
    private final TenantActionRateLimiter tenantActionRateLimiter;

    /**
     * WAITING partition 会在这里重新进入资源判断，只有满足窗口/并发/worker 条件才会真正出队。
     */
    @Scheduled(fixedDelayString = "${batch.resource-scheduler.waiting-dispatch-interval-millis:10000}")
    @SchedulerLock(name = "waiting_partition_dispatch", lockAtMostFor = "PT1M", lockAtLeastFor = "PT5S")
    public void dispatchWaitingPartitions() {
        List<JobPartitionEntity> waitingPartitions = jobMappers.jobPartitionMapper.selectWaitingPartitionsGlobal(
                governance.resourceScheduler().getWaitingDispatchBatchSize(),
                PartitionStatus.WAITING.code()
        );
        List<WaitingDispatchCandidate> candidates = new ArrayList<>();
        for (JobPartitionEntity partition : waitingPartitions) {
            WaitingDispatchCandidate candidate = buildCandidate(partition);
            if (candidate != null) {
                candidates.add(candidate);
            }
        }
        Comparator<WaitingDispatchCandidate> comparator = Comparator
                .comparingLong(WaitingDispatchCandidate::fairnessScore).reversed()
                .thenComparing(Comparator.comparingInt(WaitingDispatchCandidate::priority).reversed())
                .thenComparingLong(WaitingDispatchCandidate::partitionId);
        candidates.stream()
                .sorted(comparator)
                .forEach(candidate -> dispatchWaitingPartition(candidate.partition(), candidate.task(), candidate.jobInstance(), candidate.decision()));
    }

    private WaitingDispatchCandidate buildCandidate(JobPartitionEntity partition) {
        if (partition == null) {
            return null;
        }
        JobTaskEntity task = jobMappers.jobTaskMapper.selectByPartitionAndSeq(partition.getTenantId(), partition.getId(), 1);
        if (task == null || !TaskStatus.CREATED.code().equals(task.getTaskStatus())) {
            return null;
        }
        JobInstanceEntity jobInstance = jobMappers.jobInstanceMapper.selectById(partition.getTenantId(), partition.getJobInstanceId());
        if (jobInstance == null) {
            return null;
        }
        JobDefinitionRecord jobDefinition = configCacheService.findEnabledJobDefinition(
                jobInstance.getTenantId(),
                jobInstance.getJobCode()
        );
        ResourceSchedulingDecision decision = resourceScheduler.schedule(buildRequest(jobInstance, partition, task, jobDefinition));
        if (!decision.isDispatchable()) {
            return null;
        }
        return new WaitingDispatchCandidate(partition, task, jobInstance, decision);
    }

    private void dispatchWaitingPartition(JobPartitionEntity partition,
                                          JobTaskEntity task,
                                          JobInstanceEntity jobInstance,
                                          ResourceSchedulingDecision decision) {
        if (partition == null || task == null || jobInstance == null || decision == null || !decision.isDispatchable()) {
            return;
        }
        BatchMdc.withTenantAndTrace(jobInstance.getTenantId(), jobInstance.getTraceId(), () -> {
            BatchMdc.put(StructuredLogField.JOB_INSTANCE_ID, jobInstance.getId() == null ? null : String.valueOf(jobInstance.getId()));
            try {
                boolean allowed = tenantActionRateLimiter.tryConsume(jobInstance.getTenantId(), RateLimitAction.DISPATCH_RELEASE);
                if (!allowed) {
                    return;
                }
                if (!partitionLifecycleService.releaseForDispatch(
                        partition,
                        task,
                        PartitionStatus.WAITING.code(),
                        TaskStatus.CREATED.code()
                )) {
                    return;
                }
                taskDispatchOutboxService.writeDispatchEvent(
                        jobInstance,
                        task,
                        partition,
                        jobInstance.getTraceId(),
                        task.getTenantId() + ":" + task.getId()
                );
                if (JobInstanceStatus.WAITING.code().equals(jobInstance.getInstanceStatus())) {
                    int updated = jobMappers.jobInstanceMapper.markRunning(MarkInstanceRunningParam.builder()
                            .tenantId(jobInstance.getTenantId()).id(jobInstance.getId())
                            .instanceStatus(JobInstanceStatus.RUNNING.code())
                            .expectedPartitionCount(jobInstance.getExpectedPartitionCount())
                            .startedAt(Instant.now()).expectedVersion(jobInstance.getVersion()).build());
                    if (updated > 0) {
                        jobInstance.setVersion((jobInstance.getVersion() == null ? 0L : jobInstance.getVersion()) + 1);
                    }
                }
                WorkflowRunEntity workflowRun = workflowMappers.workflowRunMapper.selectByRelatedJobInstanceId(jobInstance.getTenantId(), jobInstance.getId());
                if (workflowRun != null && WorkflowRunStatus.CREATED.code().equals(workflowRun.getRunStatus())) {
                    workflowMappers.workflowRunMapper.markRunning(
                            workflowRun.getTenantId(),
                            workflowRun.getId(),
                            WorkflowRunStatus.RUNNING.code(),
                            workflowRun.getCurrentNodeCode(),
                            Instant.now()
                    );
                }
                log.info("waiting partition released: tenantId={}, partitionId={}, taskId={}, fairnessScore={}, tenantWeight={}, queueWeight={}",
                        partition.getTenantId(),
                        partition.getId(),
                        task.getId(),
                        decision.getFairnessScore(),
                        decision.getTenantWeight(),
                        decision.getQueueWeight());
            } finally {
                BatchMdc.remove(StructuredLogField.JOB_INSTANCE_ID);
            }
        });
    }

    private ResourceSchedulingRequest buildRequest(JobInstanceEntity jobInstance,
                                                   JobPartitionEntity partition,
                                                   JobTaskEntity task,
                                                   JobDefinitionRecord jobDefinition) {
        ResourceSchedulingRequest request = new ResourceSchedulingRequest();
        request.setTenantId(jobInstance.getTenantId());
        request.setJobCode(jobInstance.getJobCode());
        request.setQueueCode(jobInstance.getQueueCode());
        request.setWorkerGroup(partition.getWorkerGroup() == null ? jobInstance.getWorkerGroup() : partition.getWorkerGroup());
        request.setWorkerType(task.getTaskType());
        request.setPriority(jobInstance.getPriority());
        request.setRequestedPartitionCount(1);
        request.setWindowCode(jobDefinition == null ? null : jobDefinition.windowCode());
        return request;
    }

    private record WaitingDispatchCandidate(JobPartitionEntity partition,
                                            JobTaskEntity task,
                                            JobInstanceEntity jobInstance,
                                            ResourceSchedulingDecision decision) {

        private long fairnessScore() {
            return decision == null || decision.getFairnessScore() == null ? 0L : decision.getFairnessScore();
        }

        private int priority() {
            return jobInstance == null || jobInstance.getPriority() == null ? 5 : jobInstance.getPriority();
        }

        private long partitionId() {
            return partition == null || partition.getId() == null ? Long.MAX_VALUE : partition.getId();
        }
    }
}
