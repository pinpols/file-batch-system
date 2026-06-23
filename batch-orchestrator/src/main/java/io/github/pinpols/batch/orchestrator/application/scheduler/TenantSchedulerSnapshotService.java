package io.github.pinpols.batch.orchestrator.application.scheduler;

import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.common.enums.WorkerRegistryStatus;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.config.ResourceSchedulerProperties;
import io.github.pinpols.batch.orchestrator.controller.response.SchedulerSnapshotResponse;
import io.github.pinpols.batch.orchestrator.domain.entity.ResourceQueueEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.TenantQuotaPolicyEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.TenantSchedulerSnapshotEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkerRegistryEntity;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.ResourceQueueMapper;
import io.github.pinpols.batch.orchestrator.mapper.TenantQuotaPolicyMapper;
import io.github.pinpols.batch.orchestrator.mapper.TenantSchedulerSnapshotMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkerRegistryMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 租户调度快照服务：聚合配额策略、资源队列和 Worker 负载的实时视图，供监控和运维查询。
 *
 * <p>{@link #buildLive} 构建当前时刻的实时快照：统计活跃作业/分区数，查询每条配额策略的 burst 窗口状态（通过 {@link
 * QuotaRuntimeStateService#describe}），以及各队列和在线 Worker 的负载。 {@link #history} 返回最近 N 条（上限 {@value
 * #MAX_SNAPSHOT_LIMIT}）历史快照记录。
 */
@Service
@RequiredArgsConstructor
public class TenantSchedulerSnapshotService {

  private static final int MAX_SNAPSHOT_LIMIT = 100;

  private final TenantQuotaPolicyMapper tenantQuotaPolicyMapper;
  private final TenantSchedulerSnapshotMapper tenantSchedulerSnapshotMapper;
  private final ResourceQueueMapper resourceQueueMapper;
  private final JobInstanceMapper jobInstanceMapper;
  private final JobPartitionMapper jobPartitionMapper;
  private final WorkerRegistryMapper workerRegistryMapper;
  private final QuotaRuntimeStateService quotaRuntimeStateService;
  private final ResourceSchedulerProperties resourceSchedulerProperties;

  public SchedulerSnapshotResponse buildLive(String tenantId) {
    if (!Texts.hasText(tenantId)) {
      return new SchedulerSnapshotResponse(
          BatchDateTimeSupport.utcNow(), tenantId, List.of(), List.of(), List.of());
    }
    long tenantActiveJobs = jobInstanceMapper.countActiveByTenant(tenantId);
    long tenantActivePartitions =
        jobPartitionMapper.countActiveByTenant(
            tenantId,
            PartitionStatus.WAITING.code(),
            PartitionStatus.READY.code(),
            PartitionStatus.RUNNING.code(),
            PartitionStatus.RETRYING.code());

    List<SchedulerSnapshotResponse.PolicySnapshot> policies =
        buildQuotaSnapshot(tenantId, tenantActiveJobs, tenantActivePartitions);
    List<SchedulerSnapshotResponse.QueueSnapshot> queues = buildQueueSnapshot(tenantId);
    List<SchedulerSnapshotResponse.WorkerLoadSnapshot> workers = buildInstanceSnapshot(tenantId);
    return new SchedulerSnapshotResponse(
        BatchDateTimeSupport.utcNow(), tenantId, policies, queues, workers);
  }

  private List<SchedulerSnapshotResponse.PolicySnapshot> buildQuotaSnapshot(
      String tenantId, long tenantActiveJobs, long tenantActivePartitions) {
    List<SchedulerSnapshotResponse.PolicySnapshot> policies = new ArrayList<>();
    List<TenantQuotaPolicyEntity> quotaRows =
        tenantQuotaPolicyMapper.selectByTenantAndEnabled(tenantId, true);
    // R7-A3-P1: 把 N 条 countActiveByFairShareGroup 单查改成 1 条 GROUP BY 预聚合。
    Set<String> groups = new LinkedHashSet<>();
    for (TenantQuotaPolicyEntity p : quotaRows) {
      if (Texts.hasText(p.fairShareGroup())) {
        groups.add(p.fairShareGroup());
      }
    }
    Map<String, Long> groupCountMap = new HashMap<>();
    if (!groups.isEmpty()) {
      for (Map<String, Object> row : jobInstanceMapper.countActiveByFairShareGroups(groups)) {
        groupCountMap.put(
            String.valueOf(row.get("fairShareGroup")),
            ((Number) row.getOrDefault("cnt", 0L)).longValue());
      }
    }
    for (TenantQuotaPolicyEntity p : quotaRows) {
      long groupJobs =
          Texts.hasText(p.fairShareGroup())
              ? groupCountMap.getOrDefault(p.fairShareGroup(), 0L)
              : 0L;
      int baseJobs = p.maxRunningJobsPerTenant() == null ? 0 : p.maxRunningJobsPerTenant();
      int burst = p.burstLimit() == null ? 0 : Math.max(0, p.burstLimit());
      int effJobs = baseJobs > 0 ? baseJobs + burst : 0;
      int baseParts = p.maxPartitionsPerTenant() == null ? 0 : p.maxPartitionsPerTenant();
      int pburst = p.partitionBurstLimit() == null ? 0 : Math.max(0, p.partitionBurstLimit());
      int effParts = baseParts > 0 ? baseParts + pburst : 0;
      var runtime =
          quotaRuntimeStateService.describe(
              new QuotaRuntimeStateService.QuotaDescribeRequest(
                  new QuotaRuntimeStateService.QuotaReservationOwner(
                      tenantId, "TENANT_JOBS", tenantId),
                  p.quotaResetPolicy(),
                  burst,
                  resourceSchedulerProperties.getQuotaResetSlidingWindowHours()));
      policies.add(
          new SchedulerSnapshotResponse.PolicySnapshot(
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
              effParts));
    }
    return policies;
  }

  private List<SchedulerSnapshotResponse.QueueSnapshot> buildQueueSnapshot(String tenantId) {
    List<SchedulerSnapshotResponse.QueueSnapshot> queues = new ArrayList<>();
    List<ResourceQueueEntity> queueRows =
        resourceQueueMapper.selectByTenantAndEnabled(tenantId, true);
    // R7-A3-P1: queue 同样从 N 条 countActiveByTenantAndQueueCode 改 1 条 GROUP BY 预聚合。
    Set<String> queueCodes = new LinkedHashSet<>();
    for (ResourceQueueEntity q : queueRows) {
      queueCodes.add(q.queueCode());
    }
    Map<String, Long> queueCountMap = new HashMap<>();
    if (!queueCodes.isEmpty()) {
      for (Map<String, Object> row :
          jobInstanceMapper.countActiveByTenantAndQueueCodes(tenantId, queueCodes)) {
        queueCountMap.put(
            String.valueOf(row.get("queueCode")),
            ((Number) row.getOrDefault("cnt", 0L)).longValue());
      }
    }
    for (ResourceQueueEntity q : queueRows) {
      long qj = queueCountMap.getOrDefault(q.queueCode(), 0L);
      int qmax = q.maxRunningJobs() == null ? 0 : q.maxRunningJobs();
      int qburst = q.burstLimit() == null ? 0 : Math.max(0, q.burstLimit());
      int qeff = qmax > 0 ? qmax + qburst : 0;
      var runtime =
          quotaRuntimeStateService.describe(
              new QuotaRuntimeStateService.QuotaDescribeRequest(
                  new QuotaRuntimeStateService.QuotaReservationOwner(
                      tenantId, "QUEUE_JOBS", q.queueCode()),
                  q.quotaResetPolicy(),
                  qburst,
                  resourceSchedulerProperties.getQuotaResetSlidingWindowHours()));
      queues.add(
          new SchedulerSnapshotResponse.QueueSnapshot(
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
              qj));
    }
    return queues;
  }

  private List<SchedulerSnapshotResponse.WorkerLoadSnapshot> buildInstanceSnapshot(
      String tenantId) {
    List<WorkerRegistryEntity> workers =
        workerRegistryMapper.selectByTenantAndStatus(tenantId, WorkerRegistryStatus.ONLINE.code());
    List<SchedulerSnapshotResponse.WorkerLoadSnapshot> wl = new ArrayList<>();
    for (WorkerRegistryEntity w : workers) {
      wl.add(
          new SchedulerSnapshotResponse.WorkerLoadSnapshot(
              w.workerCode(), w.workerGroup(), w.currentLoad(), w.heartbeatAt(), w.status()));
    }
    return wl;
  }

  public List<TenantSchedulerSnapshotEntity> history(String tenantId, int limit) {
    int boundedLimit = Math.min(Math.max(limit, 1), MAX_SNAPSHOT_LIMIT);
    return tenantSchedulerSnapshotMapper.listRecent(tenantId, boundedLimit);
  }
}
