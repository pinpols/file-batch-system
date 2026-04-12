package com.example.batch.orchestrator.infrastructure.scheduler;

import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.application.scheduler.TenantSchedulerSnapshotService;
import com.example.batch.orchestrator.controller.response.SchedulerSnapshotResponse;
import com.example.batch.orchestrator.domain.entity.TenantSchedulerSnapshotRecord;
import com.example.batch.orchestrator.domain.value.JsonbString;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import com.example.batch.orchestrator.repository.TenantQuotaPolicyRepository;
import com.example.batch.orchestrator.repository.TenantSchedulerSnapshotRepository;
import com.example.batch.orchestrator.repository.WorkerRegistryRepository;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定期为每个租户持久化一行紧凑的快照记录，用于审计（公平份额 / 突发限制 / 分组负载）。 */
@Component
@RequiredArgsConstructor
public class TenantSchedulerSnapshotRecorder {

  private final TenantQuotaPolicyRepository tenantQuotaPolicyRepository;
  private final TenantSchedulerSnapshotService snapshotService;
  private final TenantSchedulerSnapshotRepository snapshotRepository;
  private final WorkerRegistryRepository workerRegistryRepository;
  private final OrchestratorGracefulShutdown gracefulShutdown;

  @Value("${batch.scheduler.snapshot-persist-enabled:true}")
  private boolean persistEnabled;

  @Scheduled(fixedDelayString = "${batch.scheduler.snapshot-persist-ms:120000}")
  @SchedulerLock(
      name = "tenant_scheduler_snapshot",
      lockAtMostFor = "PT5M",
      lockAtLeastFor = "PT1M")
  public void persist() {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    if (!persistEnabled) {
      return;
    }
    for (String tenantId : tenantQuotaPolicyRepository.findDistinctTenantIdsEnabled()) {
      SchedulerSnapshotResponse snap = snapshotService.buildLive(tenantId);
      if (snap.policies().isEmpty()) {
        continue;
      }
      SchedulerSnapshotResponse.PolicySnapshot p = snap.policies().getFirst();
      TenantSchedulerSnapshotRecord row =
          new TenantSchedulerSnapshotRecord(
              null,
              tenantId,
              snap.generatedAt(),
              p.fairShareGroup(),
              p.policyCode(),
              (int) Math.min(Integer.MAX_VALUE, p.activeJobs()),
              (int) Math.min(Integer.MAX_VALUE, p.activePartitions()),
              p.maxRunningJobsPerTenant(),
              p.burstLimit(),
              p.effectiveTenantJobCap(),
              (int) Math.min(Integer.MAX_VALUE, p.groupActiveJobs()),
              p.groupSharedMaxRunningJobs(),
              p.quotaResetPolicy(),
              (int)
                  workerRegistryRepository.countByTenantIdAndStatus(
                      tenantId, WorkerRegistryStatus.ONLINE.code()),
              JsonbString.of(JsonUtils.toJson(snap)));
      snapshotRepository.save(row);
    }
  }
}
