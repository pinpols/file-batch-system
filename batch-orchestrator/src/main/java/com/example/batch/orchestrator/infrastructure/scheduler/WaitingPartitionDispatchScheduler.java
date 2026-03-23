package com.example.batch.orchestrator.infrastructure.scheduler;

import com.example.batch.common.enums.JobInstanceStatus;
import com.example.batch.common.enums.PartitionStatus;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.enums.WorkflowRunStatus;
import com.example.batch.orchestrator.application.engine.TaskDispatchOutboxService;
import com.example.batch.orchestrator.application.scheduler.ResourceScheduler;
import com.example.batch.orchestrator.application.service.PartitionLifecycleService;
import com.example.batch.orchestrator.config.ResourceSchedulerProperties;
import com.example.batch.orchestrator.domain.entity.JobDefinitionRecord;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.common.persistence.entity.WorkflowRunEntity;
import com.example.batch.orchestrator.domain.scheduler.ResourceSchedulingDecision;
import com.example.batch.orchestrator.domain.scheduler.ResourceSchedulingRequest;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import com.example.batch.orchestrator.mapper.WorkflowRunMapper;
import com.example.batch.orchestrator.repository.JobDefinitionRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class WaitingPartitionDispatchScheduler {

    private final ResourceScheduler resourceScheduler;
    private final ResourceSchedulerProperties resourceSchedulerProperties;
    private final JobPartitionMapper jobPartitionMapper;
    private final JobTaskMapper jobTaskMapper;
    private final JobInstanceMapper jobInstanceMapper;
    private final WorkflowRunMapper workflowRunMapper;
    private final JobDefinitionRepository jobDefinitionRepository;
    private final TaskDispatchOutboxService taskDispatchOutboxService;
    private final PartitionLifecycleService partitionLifecycleService;

    /**
     * WAITING partition 会在这里重新进入资源判断，只有满足窗口/并发/worker 条件才会真正出队。
     */
    @Scheduled(fixedDelayString = "${batch.resource-scheduler.waiting-dispatch-interval-millis:10000}")
    public void dispatchWaitingPartitions() {
        List<JobPartitionEntity> waitingPartitions = jobPartitionMapper.selectWaitingPartitionsGlobal(
                resourceSchedulerProperties.getWaitingDispatchBatchSize(),
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
        JobTaskEntity task = jobTaskMapper.selectByPartitionAndSeq(partition.getTenantId(), partition.getId(), 1);
        if (task == null || !TaskStatus.CREATED.code().equals(task.getTaskStatus())) {
            return null;
        }
        JobInstanceEntity jobInstance = jobInstanceMapper.selectById(partition.getTenantId(), partition.getJobInstanceId());
        if (jobInstance == null) {
            return null;
        }
        JobDefinitionRecord jobDefinition = jobDefinitionRepository.findFirstByTenantIdAndJobCodeAndEnabled(
                jobInstance.getTenantId(),
                jobInstance.getJobCode(),
                true
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
                task.getTenantId() + ":waiting-release:" + task.getId()
        );
        if (JobInstanceStatus.WAITING.code().equals(jobInstance.getInstanceStatus())) {
            int updated = jobInstanceMapper.markRunning(
                    jobInstance.getTenantId(),
                    jobInstance.getId(),
                    JobInstanceStatus.RUNNING.code(),
                    jobInstance.getExpectedPartitionCount(),
                    Instant.now(),
                    jobInstance.getVersion()
            );
            if (updated > 0) {
                jobInstance.setVersion((jobInstance.getVersion() == null ? 0L : jobInstance.getVersion()) + 1);
            }
        }
        WorkflowRunEntity workflowRun = workflowRunMapper.selectByRelatedJobInstanceId(jobInstance.getTenantId(), jobInstance.getId());
        if (workflowRun != null && WorkflowRunStatus.CREATED.code().equals(workflowRun.getRunStatus())) {
            workflowRunMapper.markRunning(
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
        request.setWindowCode(jobDefinition == null ? null : jobDefinition.getWindowCode());
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
