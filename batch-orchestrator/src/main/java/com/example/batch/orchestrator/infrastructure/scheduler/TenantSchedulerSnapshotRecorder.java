package com.example.batch.orchestrator.infrastructure.scheduler;

import com.example.batch.common.utils.JsonUtils;
import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.orchestrator.application.scheduler.TenantSchedulerSnapshotService;
import com.example.batch.orchestrator.controller.response.SchedulerSnapshotResponse;
import com.example.batch.orchestrator.domain.entity.TenantSchedulerSnapshotRecord;
import com.example.batch.orchestrator.repository.TenantQuotaPolicyRepository;
import com.example.batch.orchestrator.repository.TenantSchedulerSnapshotRepository;
import com.example.batch.orchestrator.repository.WorkerRegistryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically persists a compact row per tenant for audit (fair-share / burst / group load).
 */
@Component
@RequiredArgsConstructor
public class TenantSchedulerSnapshotRecorder {

    private final TenantQuotaPolicyRepository tenantQuotaPolicyRepository;
    private final TenantSchedulerSnapshotService snapshotService;
    private final TenantSchedulerSnapshotRepository snapshotRepository;
    private final WorkerRegistryRepository workerRegistryRepository;

    @Value("${batch.scheduler.snapshot-persist-enabled:true}")
    private boolean persistEnabled;

    @Scheduled(fixedDelayString = "${batch.scheduler.snapshot-persist-ms:120000}")
    public void persist() {
        if (!persistEnabled) {
            return;
        }
        for (String tenantId : tenantQuotaPolicyRepository.findDistinctTenantIdsEnabled()) {
            SchedulerSnapshotResponse snap = snapshotService.buildLive(tenantId);
            if (snap.policies().isEmpty()) {
                continue;
            }
            SchedulerSnapshotResponse.PolicySnapshot p = snap.policies().getFirst();
            TenantSchedulerSnapshotRecord row = new TenantSchedulerSnapshotRecord();
            row.setTenantId(tenantId);
            row.setSnapshotAt(snap.generatedAt());
            row.setFairShareGroup(p.fairShareGroup());
            row.setPolicyCode(p.policyCode());
            row.setActiveJobs((int) Math.min(Integer.MAX_VALUE, p.activeJobs()));
            row.setActivePartitions((int) Math.min(Integer.MAX_VALUE, p.activePartitions()));
            row.setMaxJobsBase(p.maxRunningJobsPerTenant());
            row.setBurstLimit(p.burstLimit());
            row.setEffectiveJobCap(p.effectiveTenantJobCap());
            row.setGroupActiveJobs((int) Math.min(Integer.MAX_VALUE, p.groupActiveJobs()));
            row.setGroupMaxJobs(p.groupSharedMaxRunningJobs());
            row.setQuotaResetPolicy(p.quotaResetPolicy());
            row.setOnlineWorkers((int) workerRegistryRepository.countByTenantIdAndStatus(
                    tenantId,
                    WorkerRegistryStatus.ONLINE.code()
            ));
            row.setDetailJson(JsonUtils.toJson(snap));
            snapshotRepository.save(row);
        }
    }
}
