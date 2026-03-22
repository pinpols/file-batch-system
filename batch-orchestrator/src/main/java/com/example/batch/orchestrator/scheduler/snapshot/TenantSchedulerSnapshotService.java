package com.example.batch.orchestrator.scheduler.snapshot;

import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.orchestrator.domain.entity.ResourceQueueRecord;
import com.example.batch.orchestrator.config.ResourceSchedulerProperties;
import com.example.batch.orchestrator.domain.entity.TenantQuotaPolicyRecord;
import com.example.batch.orchestrator.domain.entity.WorkerRegistryRecord;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import com.example.batch.orchestrator.repository.ResourceQueueRepository;
import com.example.batch.orchestrator.repository.TenantQuotaPolicyRepository;
import com.example.batch.orchestrator.repository.WorkerRegistryRepository;
import com.example.batch.orchestrator.scheduler.quota.QuotaRuntimeStateService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class TenantSchedulerSnapshotService {

    private final TenantQuotaPolicyRepository tenantQuotaPolicyRepository;
    private final ResourceQueueRepository resourceQueueRepository;
    private final JobInstanceMapper jobInstanceMapper;
    private final JobPartitionMapper jobPartitionMapper;
    private final WorkerRegistryRepository workerRegistryRepository;
    private final QuotaRuntimeStateService quotaRuntimeStateService;
    private final ResourceSchedulerProperties resourceSchedulerProperties;

    public SchedulerSnapshotResponse buildLive(String tenantId) {
        if (!StringUtils.hasText(tenantId)) {
            return new SchedulerSnapshotResponse(Instant.now(), tenantId, List.of(), List.of(), List.of());
        }
        long tenantActiveJobs = jobInstanceMapper.countActiveByTenant(tenantId);
        long tenantActivePartitions = jobPartitionMapper.countActiveByTenant(tenantId);

        List<SchedulerSnapshotResponse.PolicySnapshot> policies = new ArrayList<>();
        List<TenantQuotaPolicyRecord> quotaRows = tenantQuotaPolicyRepository.findByTenantIdAndEnabled(tenantId, true);
        for (TenantQuotaPolicyRecord p : quotaRows) {
            long groupJobs = 0L;
            if (StringUtils.hasText(p.getFairShareGroup())) {
                groupJobs = jobInstanceMapper.countActiveByFairShareGroup(p.getFairShareGroup());
            }
            int baseJobs = p.getMaxRunningJobsPerTenant() == null ? 0 : p.getMaxRunningJobsPerTenant();
            int burst = p.getBurstLimit() == null ? 0 : Math.max(0, p.getBurstLimit());
            int effJobs = baseJobs > 0 ? baseJobs + burst : 0;
            int baseParts = p.getMaxPartitionsPerTenant() == null ? 0 : p.getMaxPartitionsPerTenant();
            int pburst = p.getPartitionBurstLimit() == null ? 0 : Math.max(0, p.getPartitionBurstLimit());
            int effParts = baseParts > 0 ? baseParts + pburst : 0;
            var runtime = quotaRuntimeStateService.describe(
                    tenantId,
                    "TENANT_JOBS",
                    tenantId,
                    p.getQuotaResetPolicy(),
                    burst,
                    resourceSchedulerProperties.getQuotaResetSlidingWindowHours()
            );
            policies.add(new SchedulerSnapshotResponse.PolicySnapshot(
                    p.getPolicyCode(),
                    p.getFairShareGroup(),
                    p.getFairShareWeight(),
                    p.getMaxRunningJobsPerTenant(),
                    p.getBurstLimit(),
                    p.getPartitionBurstLimit(),
                    p.getQuotaResetPolicy(),
                    runtime.peakBorrowedCount(),
                    runtime.remainingBurst(),
                    runtime.windowStartedAt(),
                    runtime.windowExpiresAt(),
                    p.getGroupSharedMaxRunningJobs(),
                    tenantActiveJobs,
                    tenantActivePartitions,
                    groupJobs,
                    effJobs,
                    effParts
            ));
        }

        List<SchedulerSnapshotResponse.QueueSnapshot> queues = new ArrayList<>();
        for (ResourceQueueRecord q : resourceQueueRepository.findByTenantIdAndEnabled(tenantId, true)) {
            long qj = jobInstanceMapper.countActiveByTenantAndQueueCode(tenantId, q.getQueueCode());
            int qmax = q.getMaxRunningJobs() == null ? 0 : q.getMaxRunningJobs();
            int qburst = q.getBurstLimit() == null ? 0 : Math.max(0, q.getBurstLimit());
            int qeff = qmax > 0 ? qmax + qburst : 0;
            var runtime = quotaRuntimeStateService.describe(
                    tenantId,
                    "QUEUE_JOBS",
                    q.getQueueCode(),
                    q.getQuotaResetPolicy(),
                    qburst,
                    resourceSchedulerProperties.getQuotaResetSlidingWindowHours()
            );
            queues.add(new SchedulerSnapshotResponse.QueueSnapshot(
                    q.getQueueCode(),
                    q.getFairShareGroup(),
                    q.getFairShareWeight(),
                    q.getMaxRunningJobs(),
                    q.getBurstLimit(),
                    qeff,
                    q.getQuotaResetPolicy(),
                    runtime.peakBorrowedCount(),
                    runtime.remainingBurst(),
                    runtime.windowStartedAt(),
                    runtime.windowExpiresAt(),
                    q.getGroupSharedMaxRunningJobs(),
                    qj
            ));
        }

        List<WorkerRegistryRecord> workers = workerRegistryRepository.findByTenantIdAndStatus(
                tenantId,
                WorkerRegistryStatus.ONLINE.code()
        );
        List<SchedulerSnapshotResponse.WorkerLoadSnapshot> wl = new ArrayList<>();
        for (WorkerRegistryRecord w : workers) {
            wl.add(new SchedulerSnapshotResponse.WorkerLoadSnapshot(
                    w.getWorkerCode(),
                    w.getWorkerGroup(),
                    w.getCurrentLoad(),
                    w.getHeartbeatAt(),
                    w.getStatus()
            ));
        }
        return new SchedulerSnapshotResponse(Instant.now(), tenantId, policies, queues, wl);
    }
}
