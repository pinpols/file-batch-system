package com.example.batch.orchestrator.application.service;

import com.example.batch.common.enums.PartitionStatus;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.exception.SystemException;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.application.plan.SchedulePlan;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DefaultPartitionLifecycleService implements PartitionLifecycleService {

    private final JobPartitionMapper jobPartitionMapper;
    private final JobTaskMapper jobTaskMapper;

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
            partitionEntity.setPartitionStatus(resolveInitialPartitionStatus(partitionPlan, initialStatus));
            partitionEntity.setWorkerGroup(plan.getWorkerGroup());
            partitionEntity.setWorkerCode(partitionPlan.getWorkerRoute() == null ? null : partitionPlan.getWorkerRoute().getWorkerId());
            partitionEntity.setRetryCount(0);
            partitionEntity.setBusinessKey(partitionPlan.getBusinessKey());
            partitionEntity.setIdempotencyKey(partitionPlan.getPartitionKey());
            partitionEntity.setInputSnapshot(buildInputSnapshot(plan, partitionPlan, jobInstanceId));
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
        List<JobPartitionEntity> expired = jobPartitionMapper.selectExpiredLeases(tenantId, PartitionStatus.READY.code(), PartitionStatus.RUNNING.code());
        for (JobPartitionEntity partition : expired) {
            reclaimed += jobPartitionMapper.markStatus(tenantId, partition.getId(), PartitionStatus.WAITING.code(),
                    PartitionStatus.RUNNING.code(), PartitionStatus.SUCCESS.code(), PartitionStatus.FAILED.code(), PartitionStatus.CANCELLED.code(), PartitionStatus.TERMINATED.code());
        }
        return reclaimed;
    }

    /**
     * READY 出队前统一做分片与任务状态同步推进，避免 launch、DAG dispatch、waiting release 各写一套状态机。
     */
    @Override
    @Transactional
    public boolean releaseForDispatch(JobPartitionEntity partition,
                                      JobTaskEntity task,
                                      String fromPartitionStatus,
                                      String fromTaskStatus) {
        if (partition == null || task == null) {
            return false;
        }
        if (jobPartitionMapper.promoteStatus(
                partition.getTenantId(),
                partition.getId(),
                fromPartitionStatus,
                PartitionStatus.READY.code()
        ) <= 0) {
            return false;
        }
        if (jobTaskMapper.promoteStatus(
                task.getTenantId(),
                task.getId(),
                fromTaskStatus,
                TaskStatus.READY.code()
        ) <= 0) {
            throw new SystemException(ResultCode.SYSTEM_ERROR, "partition and task status promotion diverged");
        }
        partition.setPartitionStatus(PartitionStatus.READY.code());
        task.setTaskStatus(TaskStatus.READY.code());
        return true;
    }

    private String resolveInitialPartitionStatus(SchedulePlan.PartitionPlan partitionPlan, String initialStatus) {
        if (partitionPlan != null && partitionPlan.getPartitionStatus() != null && !partitionPlan.getPartitionStatus().isBlank()) {
            return partitionPlan.getPartitionStatus();
        }
        return initialStatus == null || initialStatus.isBlank()
                ? PartitionStatus.CREATED.code()
                : initialStatus;
    }

    private String buildInputSnapshot(SchedulePlan plan,
                                      SchedulePlan.PartitionPlan partitionPlan,
                                      Long jobInstanceId) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("jobInstanceId", jobInstanceId);
        snapshot.put("tenantId", plan == null ? null : plan.getTenantId());
        snapshot.put("jobCode", plan == null ? null : plan.getJobCode());
        snapshot.put("bizDate", plan == null ? null : plan.getBizDate());
        snapshot.put("partitionNo", partitionPlan == null ? null : partitionPlan.getPartitionNo());
        snapshot.put("partitionKey", partitionPlan == null ? null : partitionPlan.getPartitionKey());
        snapshot.put("businessKey", partitionPlan == null ? null : partitionPlan.getBusinessKey());
        snapshot.put("workerRoute", partitionPlan == null || partitionPlan.getWorkerRoute() == null
                ? Map.of()
                : partitionPlan.getWorkerRoute());
        snapshot.put("workerGroup", plan == null ? null : plan.getWorkerGroup());
        snapshot.put("queueCode", plan == null ? null : plan.getQueueCode());
        snapshot.put("windowCode", plan == null ? null : plan.getWindowCode());
        return JsonUtils.toJson(snapshot);
    }
}
