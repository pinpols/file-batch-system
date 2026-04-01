package com.example.batch.orchestrator.application.scheduler;

import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.orchestrator.controller.response.SchedulerSnapshotResponse;
import com.example.batch.orchestrator.domain.entity.ResourceQueueRecord;
import com.example.batch.orchestrator.config.ResourceSchedulerProperties;
import com.example.batch.orchestrator.domain.entity.TenantQuotaPolicyRecord;
import com.example.batch.orchestrator.domain.entity.WorkerRegistryRecord;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.common.enums.PartitionStatus;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import com.example.batch.orchestrator.repository.ResourceQueueRepository;
import com.example.batch.orchestrator.repository.TenantQuotaPolicyRepository;
import com.example.batch.orchestrator.repository.WorkerRegistryRepository;
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
        long tenantActivePartitions = jobPartitionMapper.countActiveByTenant(tenantId, PartitionStatus.WAITING.code(), PartitionStatus.READY.code(), PartitionStatus.RUNNING.code(), PartitionStatus.RETRYING.code());

        List<SchedulerSnapshotResponse.PolicySnapshot> policies = new ArrayList<>();
        List<TenantQuotaPolicyRecord> quotaRows = tenantQuotaPolicyRepository.findByTenantIdAndEnabled(tenantId, true);
        for (TenantQuotaPolicyRecord p : quotaRows) {
            long groupJobs = 0L;
            if (StringUtils.hasText(p.fairShareGroup())) {
                groupJobs = jobInstanceMapper.countActiveByFairShareGroup(p.fairShareGroup());
            }
            int baseJobs = p.maxRunningJobsPerTenant() == null ? 0 : p.maxRunningJobsPerTenant();
            int burst = p.burstLimit() == null ? 0 : Math.max(0, p.burstLimit());
            int effJobs = baseJobs > 0 ? baseJobs + burst : 0;
            int baseParts = p.maxPartitionsPerTenant() == null ? 0 : p.maxPartitionsPerTenant();
            int pburst = p.partitionBurstLimit() == null ? 0 : Math.max(0, p.partitionBurstLimit());
            int effParts = baseParts > 0 ? baseParts + pburst : 0;
            var runtime = quotaRuntimeStateService.describe(new QuotaRuntimeStateService.QuotaDescribeRequest(
                    new QuotaRuntimeStateService.QuotaReservationOwner(
                            tenantId,
                            "TENANT_JOBS",
                            tenantId
                    ),
                    p.quotaResetPolicy(),
                    burst,
                    resourceSchedulerProperties.getQuotaResetSlidingWindowHours()
            ));
            policies.add(new SchedulerSnapshotResponse.PolicySnapshot(
                    p.policyCode(),
                    p.fairShareGroup(),
                    p.fairShareWeight(),
                    p.maxRunningJobsPerTenant(),
                    p.burstLimit(),
                    p.partitionBurstLimit(),
                    p.quotaResetPolicy(),
                    runtime.peakBorrowedCount(),
                    runtime.remainingBurst(),
                    runtime.windowStartedAt(),
                    runtime.windowExpiresAt(),
                    p.groupSharedMaxRunningJobs(),
                    tenantActiveJobs,
                    tenantActivePartitions,
                    groupJobs,
                    effJobs,
                    effParts
            ));
        }

        List<SchedulerSnapshotResponse.QueueSnapshot> queues = new ArrayList<>();
        for (ResourceQueueRecord q : resourceQueueRepository.findByTenantIdAndEnabled(tenantId, true)) {
            long qj = jobInstanceMapper.countActiveByTenantAndQueueCode(tenantId, q.queueCode());
            int qmax = q.maxRunningJobs() == null ? 0 : q.maxRunningJobs();
            int qburst = q.burstLimit() == null ? 0 : Math.max(0, q.burstLimit());
            int qeff = qmax > 0 ? qmax + qburst : 0;
            var runtime = quotaRuntimeStateService.describe(new QuotaRuntimeStateService.QuotaDescribeRequest(
                    new QuotaRuntimeStateService.QuotaReservationOwner(
                            tenantId,
                            "QUEUE_JOBS",
                            q.queueCode()
                    ),
                    q.quotaResetPolicy(),
                    qburst,
                    resourceSchedulerProperties.getQuotaResetSlidingWindowHours()
            ));
            queues.add(new SchedulerSnapshotResponse.QueueSnapshot(
                    q.queueCode(),
                    q.fairShareGroup(),
                    q.fairShareWeight(),
                    q.maxRunningJobs(),
                    q.burstLimit(),
                    qeff,
                    q.quotaResetPolicy(),
                    runtime.peakBorrowedCount(),
                    runtime.remainingBurst(),
                    runtime.windowStartedAt(),
                    runtime.windowExpiresAt(),
                    q.groupSharedMaxRunningJobs(),
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
                    w.workerCode(),
                    w.workerGroup(),
                    w.currentLoad(),
                    w.heartbeatAt(),
                    w.status()
            ));
        }
        return new SchedulerSnapshotResponse(Instant.now(), tenantId, policies, queues, wl);
    }
}
