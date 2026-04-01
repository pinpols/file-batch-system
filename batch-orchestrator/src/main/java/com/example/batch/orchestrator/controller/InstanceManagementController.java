package com.example.batch.orchestrator.controller;

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
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/instances")
@RequiredArgsConstructor
public class InstanceManagementController {

    private static final Set<String> CANCELLABLE = Set.of("CREATED", "WAITING", "READY");
    private static final Set<String> TERMINABLE = Set.of("RUNNING");
    private static final Set<String> PARTITION_CANCELLABLE = Set.of("CREATED", "WAITING", "READY");

    private final JobInstanceMapper jobInstanceMapper;
    private final JobPartitionMapper jobPartitionMapper;

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable Long id,
                                       @RequestParam("tenantId") String tenantId) {
        return transition(tenantId, id, CANCELLABLE, "CANCELLED");
    }

    @PostMapping("/{id}/terminate")
    public Map<String, Object> terminate(@PathVariable Long id,
                                          @RequestParam("tenantId") String tenantId) {
        return transition(tenantId, id, TERMINABLE, "TERMINATED");
    }

    @PostMapping("/partitions/{id}/cancel")
    public Map<String, Object> cancelPartition(@PathVariable Long id,
                                                @RequestParam("tenantId") String tenantId) {
        JobPartitionEntity partition = findPartition(tenantId, id);
        if (!PARTITION_CANCELLABLE.contains(partition.getPartitionStatus())) {
            throw new BizException(ResultCode.STATE_CONFLICT,
                    "cannot cancel partition from " + partition.getPartitionStatus());
        }
        int rows = jobPartitionMapper.promoteStatus(tenantId, id,
                partition.getPartitionStatus(), "CANCELLED", partition.getVersion());
        if (rows == 0) {
            throw new BizException(ResultCode.STATE_CONFLICT, "concurrent modification, please retry");
        }
        return Map.of("id", id, "status", "CANCELLED");
    }

    @PostMapping("/partitions/{id}/retry")
    public Map<String, Object> retryPartition(@PathVariable Long id,
                                               @RequestParam("tenantId") String tenantId) {
        JobPartitionEntity partition = findPartition(tenantId, id);
        if (!"FAILED".equals(partition.getPartitionStatus())) {
            throw new BizException(ResultCode.STATE_CONFLICT,
                    "can only retry FAILED partitions, current: " + partition.getPartitionStatus());
        }
        int rows = jobPartitionMapper.markRetrying(tenantId, id,
                partition.getRetryCount() + 1, "RETRYING", partition.getVersion());
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
        Instant finishedAt = Instant.now();
        int rows = jobInstanceMapper.updateStatus(tenantId, id, targetStatus, finishedAt, instance.getVersion());
        if (rows == 0) {
            throw new BizException(ResultCode.STATE_CONFLICT, "concurrent modification, please retry");
        }
        return Map.of("id", id, "instanceNo", instance.getInstanceNo(), "status", targetStatus);
    }
}
