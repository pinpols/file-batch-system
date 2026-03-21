package com.example.batch.orchestrator.infrastructure.lease;

import com.example.batch.common.enums.TaskStatus;
import com.example.batch.orchestrator.application.engine.TaskDispatchOutboxService;
import com.example.batch.orchestrator.config.PartitionLeaseProperties;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.query.JobTaskQuery;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class PartitionLeaseReclaimScheduler {

    private final JobPartitionMapper jobPartitionMapper;
    private final JobTaskMapper jobTaskMapper;
    private final JobInstanceMapper jobInstanceMapper;
    private final TaskDispatchOutboxService taskDispatchOutboxService;
    private final PartitionLeaseProperties partitionLeaseProperties;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(fixedDelayString = "${batch.partition-lease.reclaim-interval-millis:15000}")
    @Transactional
    public void reclaimExpiredPartitions() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            List<JobPartitionEntity> expiredPartitions = jobPartitionMapper.selectExpiredLeasesGlobal();
            expiredPartitions.forEach(this::requeueExpiredPartition);
        } finally {
            running.set(false);
        }
    }

    private void requeueExpiredPartition(JobPartitionEntity partition) {
        List<JobTaskEntity> tasks = jobTaskMapper.selectByQuery(new JobTaskQuery(
                partition.getTenantId(),
                partition.getJobInstanceId(),
                partition.getId(),
                TaskStatus.RUNNING.code(),
                null
        ));
        JobTaskEntity task = tasks.stream().findFirst().orElse(null);
        if (task == null) {
            jobPartitionMapper.resetForDispatch(partition.getTenantId(), partition.getId());
            return;
        }
        JobInstanceEntity jobInstance = jobInstanceMapper.selectById(partition.getTenantId(), partition.getJobInstanceId());
        if (jobInstance == null) {
            return;
        }
        jobPartitionMapper.resetForDispatch(partition.getTenantId(), partition.getId());
        jobTaskMapper.resetForRetry(partition.getTenantId(), task.getId());
        taskDispatchOutboxService.writeDispatchEvent(
                jobInstance,
                task,
                partition,
                jobInstance.getTraceId(),
                partition.getTenantId() + ":reclaim:" + partition.getId() + ":" + System.currentTimeMillis()
        );
        log.warn("expired partition reclaimed and re-dispatched: tenantId={}, partitionId={}, leaseWindowSeconds={}",
                partition.getTenantId(), partition.getId(), partitionLeaseProperties.getExpireSeconds());
    }
}
