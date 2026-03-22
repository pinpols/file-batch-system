package com.example.batch.orchestrator.application.service;

import com.example.batch.orchestrator.application.plan.SchedulePlan;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import java.time.Instant;
import java.util.List;

public interface PartitionLifecycleService {

    default List<JobPartitionEntity> createPartitions(SchedulePlan plan, Long jobInstanceId) {
        return createPartitions(plan, jobInstanceId, com.example.batch.common.enums.PartitionStatus.CREATED.code());
    }

    List<JobPartitionEntity> createPartitions(SchedulePlan plan, Long jobInstanceId, String initialStatus);

    JobPartitionEntity claimPartition(String tenantId, Long partitionId, String workerCode, Instant leaseExpireAt);

    JobPartitionEntity renewLease(String tenantId, Long partitionId, String workerCode, Instant leaseExpireAt);

    int reclaimExpiredPartitions(String tenantId);

    boolean releaseForDispatch(JobPartitionEntity partition,
                               JobTaskEntity task,
                               String fromPartitionStatus,
                               String fromTaskStatus);
}
