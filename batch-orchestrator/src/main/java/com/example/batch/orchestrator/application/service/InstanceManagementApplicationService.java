package com.example.batch.orchestrator.application.service;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InstanceManagementApplicationService {

    private static final Set<String> CANCELLABLE = Set.of("CREATED", "WAITING", "READY");
    private static final Set<String> TERMINABLE = Set.of("RUNNING");
    private static final Set<String> PARTITION_CANCELLABLE = Set.of("CREATED", "WAITING", "READY");

    private final JobInstanceMapper jobInstanceMapper;
    private final JobPartitionMapper jobPartitionMapper;

    public Map<String, Object> cancel(String tenantId, Long id) {
        return transition(tenantId, id, CANCELLABLE, "CANCELLED");
    }

    public Map<String, Object> terminate(String tenantId, Long id) {
        return transition(tenantId, id, TERMINABLE, "TERMINATED");
    }

    public Map<String, Object> cancelPartition(String tenantId, Long id) {
        JobPartitionEntity partition = findPartition(tenantId, id);
        if (!PARTITION_CANCELLABLE.contains(partition.getPartitionStatus())) {
            throw new BizException(ResultCode.STATE_CONFLICT,
                    "cannot cancel partition from " + partition.getPartitionStatus());
        }
        int rows = jobPartitionMapper.promoteStatus(
                tenantId,
                id,
                partition.getPartitionStatus(),
                "CANCELLED",
                partition.getVersion()
        );
        if (rows == 0) {
            throw new BizException(ResultCode.STATE_CONFLICT, "concurrent modification, please retry");
        }
        return Map.of("id", id, "status", "CANCELLED");
    }

    public Map<String, Object> retryPartition(String tenantId, Long id) {
        JobPartitionEntity partition = findPartition(tenantId, id);
        if (!"FAILED".equals(partition.getPartitionStatus())) {
            throw new BizException(ResultCode.STATE_CONFLICT,
                    "can only retry FAILED partitions, current: " + partition.getPartitionStatus());
        }
        int rows = jobPartitionMapper.markRetrying(
                tenantId,
                id,
                partition.getRetryCount() + 1,
                "RETRYING",
                partition.getVersion()
        );
        if (rows == 0) {
            throw new BizException(ResultCode.STATE_CONFLICT, "concurrent modification, please retry");
        }
        return Map.of("id", id, "status", "RETRYING");
    }

    private JobPartitionEntity findPartition(String tenantId, Long id) {
        JobPartitionEntity partition = jobPartitionMapper.selectById(tenantId, id);
        if (partition == null) {
            throw new BizException(ResultCode.NOT_FOUND, "partition not found");
        }
        return partition;
    }

    private Map<String, Object> transition(String tenantId, Long id, Set<String> allowedFrom, String targetStatus) {
        JobInstanceEntity instance = jobInstanceMapper.selectById(tenantId, id);
        if (instance == null) {
            throw new BizException(ResultCode.NOT_FOUND, "job instance not found");
        }
        if (!allowedFrom.contains(instance.getInstanceStatus())) {
            throw new BizException(ResultCode.STATE_CONFLICT,
                    "cannot transition from " + instance.getInstanceStatus() + " to " + targetStatus);
        }
        int rows = jobInstanceMapper.updateStatus(tenantId, id, targetStatus, Instant.now(), instance.getVersion());
        if (rows == 0) {
            throw new BizException(ResultCode.STATE_CONFLICT, "concurrent modification, please retry");
        }
        return Map.of("id", id, "instanceNo", instance.getInstanceNo(), "status", targetStatus);
    }
}
