package com.example.batch.orchestrator.scheduler;

import com.example.batch.orchestrator.domain.entity.ResourceQueueRecord;
import com.example.batch.orchestrator.domain.entity.TenantQuotaPolicyRecord;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import com.example.batch.orchestrator.repository.TenantQuotaPolicyRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class DefaultPartitionThrottle implements PartitionThrottle {

    private final JobPartitionMapper jobPartitionMapper;
    private final TenantQuotaPolicyRepository tenantQuotaPolicyRepository;

    @Override
    public ResourceCheck check(ResourceSchedulingRequest request, ResourceQueueRecord queue) {
        if (request == null || !StringUtils.hasText(request.getTenantId())) {
            return ResourceCheck.allow();
        }
        int requestedPartitions = Math.max(request.getRequestedPartitionCount(), 1);
        TenantQuotaPolicyRecord quotaPolicy = resolveQuotaPolicy(request.getTenantId());
        long tenantActivePartitions = jobPartitionMapper.countActiveByTenant(request.getTenantId());
        if (quotaPolicy != null
                && quotaPolicy.getMaxPartitionsPerTenant() != null
                && quotaPolicy.getMaxPartitionsPerTenant() > 0
                && tenantActivePartitions + requestedPartitions > quotaPolicy.getMaxPartitionsPerTenant()) {
            return ResourceCheck.waitForCapacity(
                    "TENANT_PARTITION_LIMIT",
                    "tenant running partitions exceed quota"
            );
        }
        if (queue != null
                && queue.getMaxRunningPartitions() != null
                && queue.getMaxRunningPartitions() > 0) {
            long queueActivePartitions = countQueueActivePartitions(request, queue, tenantActivePartitions);
            if (queueActivePartitions + requestedPartitions > queue.getMaxRunningPartitions()) {
                return ResourceCheck.waitForCapacity(
                        "QUEUE_PARTITION_LIMIT",
                        "resource queue running partitions exceed limit"
                );
            }
        }
        return ResourceCheck.allow();
    }

    private long countQueueActivePartitions(ResourceSchedulingRequest request,
                                            ResourceQueueRecord queue,
                                            long tenantActivePartitions) {
        String workerGroup = StringUtils.hasText(request.getWorkerGroup())
                ? request.getWorkerGroup()
                : queue.getWorkerGroup();
        if (!StringUtils.hasText(workerGroup)) {
            return tenantActivePartitions;
        }
        return jobPartitionMapper.countActiveByTenantAndWorkerGroup(request.getTenantId(), workerGroup);
    }

    private TenantQuotaPolicyRecord resolveQuotaPolicy(String tenantId) {
        List<TenantQuotaPolicyRecord> policies = tenantQuotaPolicyRepository.findByTenantIdAndEnabled(tenantId, true);
        return policies == null || policies.isEmpty() ? null : policies.get(0);
    }
}
