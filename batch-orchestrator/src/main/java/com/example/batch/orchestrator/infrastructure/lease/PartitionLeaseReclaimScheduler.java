package com.example.batch.orchestrator.infrastructure.lease;

import com.example.batch.common.enums.PartitionStatus;
import com.example.batch.common.enums.RunMode;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.logging.BatchMdc;
import com.example.batch.common.logging.StructuredLogField;
import com.example.batch.orchestrator.application.engine.TaskDispatchOutboxService;
import com.example.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.query.JobTaskQuery;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import com.example.batch.orchestrator.mapper.JobTaskMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class PartitionLeaseReclaimScheduler {

    private final JobPartitionMapper jobPartitionMapper;
    private final JobTaskMapper jobTaskMapper;
    private final JobInstanceMapper jobInstanceMapper;
    private final TaskDispatchOutboxService taskDispatchOutboxService;
    private final BatchOrchestratorGovernanceProperties governance;
    private final OrchestratorGracefulShutdown gracefulShutdown;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(fixedDelayString = "${batch.partition-lease.reclaim-interval-millis:15000}")
    @SchedulerLock(
            name = "partition_lease_reclaim",
            lockAtMostFor = "PT2M",
            lockAtLeastFor = "PT10S")
    @Transactional
    public void reclaimExpiredPartitions() {
        if (gracefulShutdown.isDraining()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            List<JobPartitionEntity> expiredPartitions =
                    jobPartitionMapper.selectExpiredLeasesGlobal(
                            PartitionStatus.READY.code(), PartitionStatus.RUNNING.code());
            expiredPartitions.forEach(this::requeueExpiredPartition);
        } finally {
            running.set(false);
        }
    }

    private void requeueExpiredPartition(JobPartitionEntity partition) {
        List<JobTaskEntity> tasks =
                jobTaskMapper.selectByQuery(
                        new JobTaskQuery(
                                partition.getTenantId(),
                                partition.getJobInstanceId(),
                                partition.getId(),
                                TaskStatus.RUNNING.code(),
                                null));
        JobTaskEntity task = tasks.stream().findFirst().orElse(null);
        if (task == null) {
            // 无 RUNNING task：partition 可能在 READY 状态等待被 claim，直接重置即可，版本冲突则跳过。
            int reset =
                    jobPartitionMapper.resetForDispatch(
                            partition.getTenantId(),
                            partition.getId(),
                            PartitionStatus.READY.code(),
                            partition.getVersion());
            if (reset <= 0) {
                log.debug(
                        "reclaim skipped (no-task path), partition already updated: partitionId={}",
                        partition.getId());
            }
            return;
        }
        JobInstanceEntity jobInstance =
                jobInstanceMapper.selectById(partition.getTenantId(), partition.getJobInstanceId());
        if (jobInstance == null) {
            return;
        }
        BatchMdc.withTenantAndTrace(
                jobInstance.getTenantId(),
                jobInstance.getTraceId(),
                () -> {
                    BatchMdc.put(
                            StructuredLogField.JOB_INSTANCE_ID,
                            jobInstance.getId() == null
                                    ? null
                                    : String.valueOf(jobInstance.getId()));
                    try {
                        // 两步 CAS 必须都成功才写 outbox，否则说明 worker 仍在执行（version 已变），跳过本次 reclaim。
                        if (jobPartitionMapper.resetForDispatch(
                                        partition.getTenantId(),
                                        partition.getId(),
                                        PartitionStatus.READY.code(),
                                        partition.getVersion())
                                <= 0) {
                            log.warn(
                                    "reclaim skipped, partition version conflict: partitionId={}",
                                    partition.getId());
                            return;
                        }
                        if (jobTaskMapper.resetForRetry(
                                        partition.getTenantId(),
                                        task.getId(),
                                        TaskStatus.READY.code(),
                                        task.getVersion())
                                <= 0) {
                            log.warn(
                                    "reclaim skipped, task version conflict: taskId={}",
                                    task.getId());
                            // partition 已被 resetForDispatch 重置为 READY；task 版本冲突说明状态仍有效，
                            // 不写 outbox 以避免重复派发，下次 reclaim 周期重试。
                            return;
                        }
                        taskDispatchOutboxService.writeDispatchEvent(
                                jobInstance,
                                task,
                                partition,
                                jobInstance.getTraceId(),
                                partition.getTenantId() + ":reclaim:" + partition.getId(),
                                RunMode.RECOVER);
                        log.warn(
                                "expired partition reclaimed and re-dispatched: tenantId={},"
                                    + " partitionId={}, leaseWindowSeconds={}",
                                partition.getTenantId(),
                                partition.getId(),
                                governance.partitionLease().getExpireSeconds());
                    } finally {
                        BatchMdc.remove(StructuredLogField.JOB_INSTANCE_ID);
                    }
                });
    }
}
