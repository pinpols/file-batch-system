package com.example.batch.orchestrator.infrastructure.scheduler;

import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.common.rls.RlsTenantContextHolder;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.application.scheduler.TenantSchedulerSnapshotService;
import com.example.batch.orchestrator.controller.response.SchedulerSnapshotResponse;
import com.example.batch.orchestrator.domain.entity.TenantSchedulerSnapshotEntity;
import com.example.batch.orchestrator.domain.value.JsonbString;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import com.example.batch.orchestrator.mapper.TenantQuotaPolicyMapper;
import com.example.batch.orchestrator.mapper.TenantSchedulerSnapshotMapper;
import com.example.batch.orchestrator.mapper.WorkerRegistryMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定期为每个租户持久化一行紧凑的快照记录，用于审计（公平份额 / 突发限制 / 分组负载）。 */
@Component
@RequiredArgsConstructor
public class TenantSchedulerSnapshotRecorder {

  private final TenantQuotaPolicyMapper tenantQuotaPolicyMapper;
  private final TenantSchedulerSnapshotService snapshotService;
  private final TenantSchedulerSnapshotMapper snapshotMapper;
  private final WorkerRegistryMapper workerRegistryMapper;
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
    List<String> tenantIds = tenantQuotaPolicyMapper.selectDistinctEnabledTenantIds();
    if (tenantIds.isEmpty()) {
      return;
    }
    // 一次 GROUP BY 查所有租户的 ONLINE worker 数,取代 N 次 countByTenantAndStatus
    Map<String, Long> onlineByTenant = new HashMap<>();
    for (Map<String, Object> row :
        workerRegistryMapper.countByTenantsAndStatus(
            tenantIds, WorkerRegistryStatus.ONLINE.code())) {
      String tid = String.valueOf(row.get("tenant_id"));
      Object cnt = row.get("cnt");
      onlineByTenant.put(tid, cnt instanceof Number n ? n.longValue() : 0L);
    }
    List<TenantSchedulerSnapshotEntity> rows = new ArrayList<>(tenantIds.size());
    for (String tenantId : tenantIds) {
      if (tenantId == null || tenantId.isBlank()) {
        continue;
      }
      // RLS Phase B：buildLive 内部走 mapper 查 quota / partition 统计；必须在绑定的租户上下文里做。
      SchedulerSnapshotResponse snap =
          RlsTenantContextHolder.runWithTenant(tenantId, () -> snapshotService.buildLive(tenantId));
      if (snap.policies().isEmpty()) {
        continue;
      }
      SchedulerSnapshotResponse.PolicySnapshot p = snap.policies().getFirst();
      long online = onlineByTenant.getOrDefault(tenantId, 0L);
      rows.add(
          new TenantSchedulerSnapshotEntity(
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
              (int) Math.min(Integer.MAX_VALUE, online),
              JsonbString.of(JsonUtils.toJson(snap))));
    }
    if (!rows.isEmpty()) {
      // 批量 INSERT:单条 SQL 多 VALUES,N 次 round-trip 降为 1 次
      snapshotMapper.insertBatch(rows);
    }
  }
}
