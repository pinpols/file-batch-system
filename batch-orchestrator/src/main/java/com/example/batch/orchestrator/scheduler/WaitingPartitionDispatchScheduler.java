package com.example.batch.orchestrator.scheduler;

import com.example.batch.common.enums.JobInstanceStatus;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.enums.WorkflowRunStatus;
import com.example.batch.orchestrator.application.engine.TaskDispatchOutboxService;
import com.example.batch.orchestrator.config.ResourceSchedulerProperties;
import com.example.batch.orchestrator.domain.entity.JobDefinitionRecord;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowRunEntity;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import com.example.batch.orchestrator.mapper.WorkflowRunMapper;
import com.example.batch.orchestrator.repository.JobDefinitionRepository;
import java.time.Instant;
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

    /**
     * WAITING partition 会在这里重新进入资源判断，只有满足窗口/并发/worker 条件才会真正出队。
     */
    @Scheduled(fixedDelayString = "${batch.resource-scheduler.waiting-dispatch-interval-millis:10000}")
    public void dispatchWaitingPartitions() {
        List<JobPartitionEntity> waitingPartitions = jobPartitionMapper.selectWaitingPartitionsGlobal(
                resourceSchedulerProperties.getWaitingDispatchBatchSize()
        );
        waitingPartitions.stream()
                .sorted(Comparator.comparingInt(this::resolvePriorityScore).reversed())
                .forEach(this::dispatchWaitingPartition);
    }

    private void dispatchWaitingPartition(JobPartitionEntity partition) {
            if (partition == null) {
                return;
            }
            JobTaskEntity task = jobTaskMapper.selectByPartitionAndSeq(partition.getTenantId(), partition.getId(), 1);
            if (task == null || !TaskStatus.CREATED.code().equals(task.getTaskStatus())) {
                return;
            }
            JobInstanceEntity jobInstance = jobInstanceMapper.selectById(partition.getTenantId(), partition.getJobInstanceId());
            if (jobInstance == null) {
                return;
            }
            JobDefinitionRecord jobDefinition = jobDefinitionRepository.findFirstByTenantIdAndJobCodeAndEnabled(
                    jobInstance.getTenantId(),
                    jobInstance.getJobCode(),
                    true
            );
            ResourceSchedulingDecision decision = resourceScheduler.schedule(buildRequest(jobInstance, partition, task, jobDefinition));
            if (!decision.isDispatchable()) {
                return;
            }
            if (jobPartitionMapper.promoteStatus(
                    partition.getTenantId(),
                    partition.getId(),
                    com.example.batch.common.enums.PartitionStatus.WAITING.code(),
                    com.example.batch.common.enums.PartitionStatus.READY.code()
            ) <= 0) {
                return;
            }
            if (jobTaskMapper.promoteStatus(
                    task.getTenantId(),
                    task.getId(),
                    TaskStatus.CREATED.code(),
                    TaskStatus.READY.code()
            ) <= 0) {
                return;
            }
            JobPartitionEntity readyPartition = jobPartitionMapper.selectById(partition.getTenantId(), partition.getId());
            JobTaskEntity readyTask = jobTaskMapper.selectById(task.getTenantId(), task.getId());
            taskDispatchOutboxService.writeDispatchEvent(
                    jobInstance,
                    readyTask,
                    readyPartition,
                    jobInstance.getTraceId(),
                    task.getTenantId() + ":waiting-release:" + task.getId()
            );
            if (JobInstanceStatus.WAITING.code().equals(jobInstance.getInstanceStatus())) {
                jobInstanceMapper.markRunning(
                        jobInstance.getTenantId(),
                        jobInstance.getId(),
                        JobInstanceStatus.RUNNING.code(),
                        jobInstance.getExpectedPartitionCount(),
                        Instant.now()
                );
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
            log.info("waiting partition released: tenantId={}, partitionId={}, taskId={}",
                    partition.getTenantId(), partition.getId(), task.getId());
    }

    private int resolvePriorityScore(JobPartitionEntity partition) {
        if (partition == null) {
            return 0;
        }
        JobInstanceEntity jobInstance = jobInstanceMapper.selectById(partition.getTenantId(), partition.getJobInstanceId());
        if (jobInstance == null || jobInstance.getPriority() == null) {
            return 5;
        }
        return 10 - jobInstance.getPriority();
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
}
