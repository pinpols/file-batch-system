package com.example.batch.orchestrator.infrastructure.scheduler;

import com.example.batch.orchestrator.application.scheduler.PartitionThrottle;
import com.example.batch.orchestrator.application.scheduler.QuotaRuntimeStateService;
import com.example.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import com.example.batch.orchestrator.domain.entity.ResourceQueueRecord;
import com.example.batch.orchestrator.domain.entity.TenantQuotaPolicyRecord;
import com.example.batch.orchestrator.domain.scheduler.ResourceCheck;
import com.example.batch.orchestrator.domain.scheduler.ResourceSchedulingRequest;
import com.example.batch.common.enums.PartitionStatus;
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
    private final QuotaRuntimeStateService quotaRuntimeStateService;
    private final BatchOrchestratorGovernanceProperties governance;

    @Override
    public ResourceCheck check(ResourceSchedulingRequest request, ResourceQueueRecord queue) {
        if (request == null || !StringUtils.hasText(request.getTenantId())) {
            return ResourceCheck.allow();
        }
        int requestedPartitions = Math.max(request.getRequestedPartitionCount(), 1);
        TenantQuotaPolicyRecord quotaPolicy = resolveQuotaPolicy(request.getTenantId());
        long tenantActivePartitions = jobPartitionMapper.countActiveByTenant(request.getTenantId(), PartitionStatus.WAITING.code(), PartitionStatus.READY.code(), PartitionStatus.RUNNING.code(), PartitionStatus.RETRYING.code());
        if (quotaPolicy != null
                && quotaPolicy.maxPartitionsPerTenant() != null
                && quotaPolicy.maxPartitionsPerTenant() > 0) {
            int pburst = quotaPolicy.partitionBurstLimit() == null ? 0 : Math.max(0, quotaPolicy.partitionBurstLimit());
            ResourceCheck burstCheck = quotaRuntimeStateService.evaluateAndReserve(
                    request.getTenantId(),
                    "TENANT_PARTITIONS",
                    request.getTenantId(),
                    quotaPolicy.quotaResetPolicy(),
                    quotaPolicy.maxPartitionsPerTenant(),
                    pburst,
                    tenantActivePartitions,
                    requestedPartitions,
                    governance.resourceScheduler().getQuotaResetSlidingWindowHours(),
                    "TENANT_PARTITION_LIMIT",
                    "tenant running partitions exceed quota (including partition burst)"
            );
            if (!burstCheck.allowed()) {
                return burstCheck;
            }
        }
        if (queue != null
                && queue.maxRunningPartitions() != null
                && queue.maxRunningPartitions() > 0) {
            long queueActivePartitions = countQueueActivePartitions(request, queue, tenantActivePartitions);
            int burst = queue.burstLimit() == null ? 0 : Math.max(0, queue.burstLimit());
            ResourceCheck burstCheck = quotaRuntimeStateService.evaluateAndReserve(
                    request.getTenantId(),
                    "QUEUE_PARTITIONS",
                    queue.queueCode(),
                    queue.quotaResetPolicy(),
                    queue.maxRunningPartitions(),
                    burst,
                    queueActivePartitions,
                    requestedPartitions,
                    governance.resourceScheduler().getQuotaResetSlidingWindowHours(),
                    "QUEUE_PARTITION_LIMIT",
                    "resource queue running partitions exceed limit"
            );
            if (!burstCheck.allowed()) {
                return burstCheck;
            }
        }
        return ResourceCheck.allow();
    }

    private long countQueueActivePartitions(ResourceSchedulingRequest request,
                                            ResourceQueueRecord queue,
                                            long tenantActivePartitions) {
        String workerGroup = StringUtils.hasText(request.getWorkerGroup())
                ? request.getWorkerGroup()
                : queue.workerGroup();
        if (!StringUtils.hasText(workerGroup)) {
            return tenantActivePartitions;
        }
        return jobPartitionMapper.countActiveByTenantAndWorkerGroup(request.getTenantId(), workerGroup, PartitionStatus.WAITING.code(), PartitionStatus.READY.code(), PartitionStatus.RUNNING.code(), PartitionStatus.RETRYING.code());
    }

    private TenantQuotaPolicyRecord resolveQuotaPolicy(String tenantId) {
        List<TenantQuotaPolicyRecord> policies = tenantQuotaPolicyRepository.findByTenantIdAndEnabled(tenantId, true);
        return policies == null || policies.isEmpty() ? null : policies.get(0);
    }
}
