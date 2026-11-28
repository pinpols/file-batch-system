package io.github.pinpols.batch.orchestrator.infrastructure.lease;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.common.enums.TaskStatus;
import io.github.pinpols.batch.orchestrator.application.engine.OutboxEventKeyGenerator;
import io.github.pinpols.batch.orchestrator.application.engine.TaskDispatchOutboxService;
import io.github.pinpols.batch.orchestrator.application.engine.command.TaskDispatchEventCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 重发已经发布、但 worker 从未认领的 READY 任务。
 *
 * <p>只推进任务版本并写一条新的 outbox。状态、assignee 和租约保持不变，晚到的认领如果先提交，CAS 就是 0 行。
 */
@Slf4j
@Component
public class UnclaimedReadyDispatchUnit {

    private final JobTaskMapper jobTaskMapper;
    private final JobPartitionMapper jobPartitionMapper;
    private final JobInstanceMapper jobInstanceMapper;
    private final TaskDispatchOutboxService taskDispatchOutboxService;
    private final Counter redispatchCounter;

    public UnclaimedReadyDispatchUnit(
            JobTaskMapper jobTaskMapper,
            JobPartitionMapper jobPartitionMapper,
            JobInstanceMapper jobInstanceMapper,
            TaskDispatchOutboxService taskDispatchOutboxService,
            MeterRegistry meterRegistry) {
        this.jobTaskMapper = jobTaskMapper;
        this.jobPartitionMapper = jobPartitionMapper;
        this.jobInstanceMapper = jobInstanceMapper;
        this.taskDispatchOutboxService = taskDispatchOutboxService;
        this.redispatchCounter = Counter.builder("batch.orchestrator.lease.unclaimed_dispatch.total")
                .description("READY tasks republished after the original dispatch was not claimed")
                .register(meterRegistry);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void redispatch(JobTaskEntity scanned) {
        if (scanned == null || scanned.getId() == null) {
            return;
        }
        String tenantId = scanned.getTenantId();
        jobInstanceMapper.acquireInstanceAdvisoryLock(tenantId, scanned.getJobInstanceId());
        JobTaskEntity task = jobTaskMapper.selectForWorkerClaim(tenantId, scanned.getId());
        if (!isUnclaimedReady(task)) {
            return;
        }
        JobPartitionEntity partition = jobPartitionMapper.selectById(tenantId, task.getJobPartitionId());
        boolean partitionIsNotWaitingForClaim = partition == null
                || !PartitionStatus.READY.code().equals(partition.getPartitionStatus())
                || partition.getLeaseExpireAt() != null;
        if (partitionIsNotWaitingForClaim) {
            return;
        }
        JobInstanceEntity jobInstance = jobInstanceMapper.selectById(tenantId, task.getJobInstanceId());
        if (jobInstance == null) {
            return;
        }
        int updated = jobTaskMapper.bumpVersionIfUnclaimedReady(tenantId, task.getId(), task.getVersion());
        if (updated <= 0) {
            return;
        }
        long dispatchedVersion = task.getVersion() + 1;
        String eventKey = OutboxEventKeyGenerator.forUnclaimedRedispatch(tenantId, task.getId(), dispatchedVersion);
        taskDispatchOutboxService.writeDispatchEvent(TaskDispatchEventCommand.builder()
                .jobInstance(jobInstance)
                .task(task)
                .partition(partition)
                .traceId(jobInstance.getTraceId())
                .eventKey(eventKey)
                .build());
        redispatchCounter.increment();
        log.warn(
                "unclaimed READY task re-dispatched: tenantId={}, partitionId={}, taskId={},"
                        + " fromVersion={}, graceApplied=true",
                tenantId,
                partition.getId(),
                task.getId(),
                task.getVersion());
    }

    private static boolean isUnclaimedReady(JobTaskEntity task) {
        return task != null
                && TaskStatus.READY.code().equals(task.getTaskStatus())
                && task.getAssignedWorkerCode() == null
                && task.getNonIdempotentStartedAt() == null;
    }
}
