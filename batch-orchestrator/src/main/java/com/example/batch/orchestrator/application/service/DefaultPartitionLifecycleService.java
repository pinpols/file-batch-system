package com.example.batch.orchestrator.application.service;

import com.example.batch.common.enums.PartitionStatus;
import com.example.batch.orchestrator.application.plan.SchedulePlan;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultPartitionLifecycleService implements PartitionLifecycleService {

    private final JobPartitionMapper jobPartitionMapper;

    public DefaultPartitionLifecycleService(JobPartitionMapper jobPartitionMapper) {
        this.jobPartitionMapper = jobPartitionMapper;
    }

    @Override
    @Transactional
    public List<JobPartitionEntity> createPartitions(SchedulePlan plan, Long jobInstanceId, String initialStatus) {
        List<JobPartitionEntity> partitionEntities = new ArrayList<>();
        if (plan == null || plan.getPartitions() == null) {
            return partitionEntities;
        }
        for (SchedulePlan.PartitionPlan partitionPlan : plan.getPartitions()) {
            JobPartitionEntity partitionEntity = new JobPartitionEntity();
            partitionEntity.setTenantId(plan.getTenantId());
            partitionEntity.setJobInstanceId(jobInstanceId);
            partitionEntity.setPartitionNo(partitionPlan.getPartitionNo());
            partitionEntity.setPartitionKey(partitionPlan.getPartitionKey());
            partitionEntity.setPartitionStatus(initialStatus == null || initialStatus.isBlank()
                    ? PartitionStatus.CREATED.code()
                    : initialStatus);
            partitionEntity.setWorkerGroup(plan.getWorkerGroup());
            partitionEntity.setWorkerCode(partitionPlan.getWorkerRoute() == null ? null : partitionPlan.getWorkerRoute().getWorkerId());
            partitionEntity.setRetryCount(0);
            partitionEntity.setBusinessKey(partitionPlan.getBusinessKey());
            partitionEntity.setIdempotencyKey(partitionPlan.getPartitionKey());
            jobPartitionMapper.insert(partitionEntity);
            partitionEntities.add(partitionEntity);
        }
        return partitionEntities;
    }

    @Override
    @Transactional
    public JobPartitionEntity claimPartition(String tenantId, Long partitionId, String workerCode, Instant leaseExpireAt) {
        JobPartitionEntity existingPartition = jobPartitionMapper.selectById(tenantId, partitionId);
        if (existingPartition == null) {
            return null;
        }
        int updated = jobPartitionMapper.claimPartition(
                tenantId,
                partitionId,
                workerCode,
                leaseExpireAt,
                PartitionStatus.READY.code(),
                PartitionStatus.RUNNING.code()
        );
        return updated > 0 ? jobPartitionMapper.selectById(tenantId, partitionId) : existingPartition;
    }

    @Override
    @Transactional
    public JobPartitionEntity renewLease(String tenantId, Long partitionId, String workerCode, Instant leaseExpireAt) {
        JobPartitionEntity existingPartition = jobPartitionMapper.selectById(tenantId, partitionId);
        if (existingPartition == null) {
            return null;
        }
        int updated = jobPartitionMapper.renewLease(tenantId, partitionId, workerCode, leaseExpireAt);
        return updated > 0 ? jobPartitionMapper.selectById(tenantId, partitionId) : existingPartition;
    }

    @Override
    @Transactional
    public int reclaimExpiredPartitions(String tenantId) {
        int reclaimed = 0;
        List<JobPartitionEntity> expired = jobPartitionMapper.selectExpiredLeases(tenantId);
        for (JobPartitionEntity partition : expired) {
            reclaimed += jobPartitionMapper.markStatus(tenantId, partition.getId(), PartitionStatus.READY.code());
        }
        return reclaimed;
    }
}
