package io.github.pinpols.batch.orchestrator.application.scheduler;

import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.common.enums.WorkerRegistryStatus;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.config.ResourceSchedulerProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.ResourceQueueEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.TenantQuotaPolicyEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.TenantSchedulerSnapshotEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkerRegistryEntity;
import io.github.pinpols.batch.orchestrator.domain.param.QueueBacklogQueryParam;
import io.github.pinpols.batch.orchestrator.domain.query.JobPartitionActiveByTenantCountQuery;
import io.github.pinpols.batch.orchestrator.domain.view.FairShareGroupActiveCountView;
import io.github.pinpols.batch.orchestrator.domain.view.QueueActiveCountView;
import io.github.pinpols.batch.orchestrator.domain.view.QueuePartitionBacklogStats;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.ResourceQueueMapper;
import io.github.pinpols.batch.orchestrator.mapper.TenantQuotaPolicyMapper;
import io.github.pinpols.batch.orchestrator.mapper.TenantSchedulerSnapshotMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkerRegistryMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
  private static final String BOTTLENECK_NONE = "NONE";
  private static final String BOTTLENECK_QUEUE_JOB_LIMIT = "QUEUE_JOB_LIMIT";
  private static final String BOTTLENECK_QUEUE_PARTITION_LIMIT = "QUEUE_PARTITION_LIMIT";
  private static final String BOTTLENECK_NO_ONLINE_WORKER = "NO_ONLINE_WORKER";
  private static final String BOTTLENECK_WAITING_DISPATCH_BACKLOG = "WAITING_DISPATCH_BACKLOG";

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
    JobPartitionActiveByTenantCountQuery jobPartitionActiveByTenantCountQuery =
        JobPartitionActiveByTenantCountQuery.builder()
            .tenantId(tenantId)
            .waitingStatus(PartitionStatus.WAITING.code())
            .readyStatus(PartitionStatus.READY.code())
            .runningStatus(PartitionStatus.RUNNING.code())
            .retryingStatus(PartitionStatus.RETRYING.code())
            .build();
    long tenantActivePartitions =
        jobPartitionMapper.countActiveByTenant(jobPartitionActiveByTenantCountQuery);

    List<SchedulerSnapshotResponse.PolicySnapshot> policies =
        buildQuotaSnapshot(tenantId, tenantActiveJobs, tenantActivePartitions);
    List<SchedulerSnapshotResponse.WorkerLoadSnapshot> workers = buildInstanceSnapshot(tenantId);
    Set<String> onlineGroups = onlineWorkerGroups(workers);
    List<SchedulerSnapshotResponse.QueueSnapshot> queues =
        buildQueueSnapshot(tenantId, onlineGroups);
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
      for (FairShareGroupActiveCountView row :
          jobInstanceMapper.countActiveByFairShareGroups(groups)) {
        String fairShareGroup = row.fairShareGroup();
        long activeJobCount = EmptyChecks.isNull(row.cnt()) ? 0L : row.cnt();
        groupCountMap.put(fairShareGroup, activeJobCount);
      }
    }
    for (TenantQuotaPolicyEntity p : quotaRows) {
      long groupJobs = Texts.hasText(p.fairShareGroup())
          ? groupCountMap.getOrDefault(p.fairShareGroup(), 0L)
          : 0L;
      int baseJobs = p.maxRunningJobsPerTenant() == null ? 0 : p.maxRunningJobsPerTenant();
      int burst = p.burstLimit() == null ? 0 : Math.max(0, p.burstLimit());
      int effJobs = baseJobs > 0 ? baseJobs + burst : 0;
      int baseParts = p.maxPartitionsPerTenant() == null ? 0 : p.maxPartitionsPerTenant();
      int pburst = p.partitionBurstLimit() == null ? 0 : Math.max(0, p.partitionBurstLimit());
      int effParts = baseParts > 0 ? baseParts + pburst : 0;
      QuotaRuntimeStateService.QuotaReservationOwner quotaOwner =
          new QuotaRuntimeStateService.QuotaReservationOwner(tenantId, "TENANT_JOBS", tenantId);
      QuotaRuntimeStateService.QuotaDescribeRequest quotaRequest =
          new QuotaRuntimeStateService.QuotaDescribeRequest(
              quotaOwner,
              p.quotaResetPolicy(),
              burst,
              resourceSchedulerProperties.getQuotaResetSlidingWindowHours());
      QuotaRuntimeStateService.QuotaRuntimeSnapshot runtime =
          quotaRuntimeStateService.describe(quotaRequest);
      SchedulerSnapshotResponse.PolicySnapshot policySnapshot =
          SchedulerSnapshotResponse.PolicySnapshot.builder()
              .policyCode(p.policyCode())
              .fairShareGroup(p.fairShareGroup())
              .fairShareWeight(p.fairShareWeight())
              .maxRunningJobsPerTenant(p.maxRunningJobsPerTenant())
              .burstLimit(p.burstLimit())
              .partitionBurstLimit(p.partitionBurstLimit())
              .quotaResetPolicy(p.quotaResetPolicy())
              .quotaBurstPeakBorrowed(runtime.peakBorrowedCount())
              .quotaBurstRemaining(runtime.remainingBurst())
              .quotaResetWindowStartedAt(runtime.windowStartedAt())
              .quotaResetWindowExpiresAt(runtime.windowExpiresAt())
              .groupSharedMaxRunningJobs(p.groupSharedMaxRunningJobs())
              .activeJobs(tenantActiveJobs)
              .activePartitions(tenantActivePartitions)
              .groupActiveJobs(groupJobs)
              .effectiveTenantJobCap(effJobs)
              .effectiveTenantPartitionCap(effParts)
              .build();
      policies.add(policySnapshot);
    }
    return policies;
  }

  private List<SchedulerSnapshotResponse.QueueSnapshot> buildQueueSnapshot(
      String tenantId, Set<String> onlineWorkerGroups) {
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
      for (QueueActiveCountView row :
          jobInstanceMapper.countActiveByTenantAndQueueCodes(tenantId, queueCodes)) {
        String queueCode = row.queueCode();
        long activeJobCount = EmptyChecks.isNull(row.cnt()) ? 0L : row.cnt();
        queueCountMap.put(queueCode, activeJobCount);
      }
    }
    Map<String, QueuePartitionBacklogStats> backlogMap = new HashMap<>();
    if (!queueCodes.isEmpty()) {
      QueueBacklogQueryParam backlogQuery = new QueueBacklogQueryParam(
          tenantId,
          List.copyOf(queueCodes),
          PartitionStatus.CREATED.code(),
          PartitionStatus.WAITING.code(),
          PartitionStatus.READY.code(),
          PartitionStatus.RUNNING.code(),
          PartitionStatus.RETRYING.code());
      for (QueuePartitionBacklogStats row :
          jobPartitionMapper.summarizeQueueBacklogByTenantAndQueueCodes(backlogQuery)) {
        backlogMap.put(row.queueCode(), row);
      }
    }
    long tenantWaitingPartitions = backlogMap.values().stream()
        .mapToLong(QueuePartitionBacklogStats::waitingPartitions)
        .sum();
    for (ResourceQueueEntity queue : queueRows) {
      SchedulerSnapshotResponse.QueueSnapshot queueSnapshot = buildQueueView(
          tenantId, queue, queueCountMap, backlogMap, tenantWaitingPartitions, onlineWorkerGroups);
      queues.add(queueSnapshot);
    }
    return queues;
  }

  private SchedulerSnapshotResponse.QueueSnapshot buildQueueView(
      String tenantId,
      ResourceQueueEntity queue,
      Map<String, Long> queueCountMap,
      Map<String, QueuePartitionBacklogStats> backlogMap,
      long tenantWaitingPartitions,
      Set<String> onlineWorkerGroups) {
    long activeJobs = queueCountMap.getOrDefault(queue.queueCode(), 0L);
    int maxRunningJobs = queue.maxRunningJobs() == null ? 0 : queue.maxRunningJobs();
    int burstLimit = queue.burstLimit() == null ? 0 : Math.max(0, queue.burstLimit());
    int effectiveMaxRunningJobs = maxRunningJobs > 0 ? maxRunningJobs + burstLimit : 0;
    int partitionCap = queue.maxRunningPartitions() == null ? 0 : queue.maxRunningPartitions();
    QueuePartitionBacklogStats backlog =
        backlogMap.getOrDefault(queue.queueCode(), emptyBacklog(queue));
    QuotaRuntimeStateService.QuotaReservationOwner quotaOwner =
        new QuotaRuntimeStateService.QuotaReservationOwner(
            tenantId, "QUEUE_JOBS", queue.queueCode());
    QuotaRuntimeStateService.QuotaDescribeRequest quotaRequest =
        new QuotaRuntimeStateService.QuotaDescribeRequest(
            quotaOwner,
            queue.quotaResetPolicy(),
            burstLimit,
            resourceSchedulerProperties.getQuotaResetSlidingWindowHours());
    QuotaRuntimeStateService.QuotaRuntimeSnapshot runtime =
        quotaRuntimeStateService.describe(quotaRequest);
    int waitingSharePermille = permille(backlog.waitingPartitions(), tenantWaitingPartitions);
    int activePartitionPermille = permille(backlog.activePartitions(), partitionCap);
    String queueBottleneckReason =
        bottleneckReason(queue, activeJobs, effectiveMaxRunningJobs, backlog, onlineWorkerGroups);
    SchedulerSnapshotResponse.QueueSnapshot queueSnapshot =
        SchedulerSnapshotResponse.QueueSnapshot.builder()
            .queueCode(queue.queueCode())
            .fairShareGroup(queue.fairShareGroup())
            .fairShareWeight(queue.fairShareWeight())
            .maxRunningJobs(queue.maxRunningJobs())
            .maxRunningPartitions(queue.maxRunningPartitions())
            .burstLimit(queue.burstLimit())
            .effectiveMaxRunningJobs(effectiveMaxRunningJobs)
            .effectiveMaxRunningPartitions(partitionCap)
            .quotaResetPolicy(queue.quotaResetPolicy())
            .quotaBurstPeakBorrowed(runtime.peakBorrowedCount())
            .quotaBurstRemaining(runtime.remainingBurst())
            .quotaResetWindowStartedAt(runtime.windowStartedAt())
            .quotaResetWindowExpiresAt(runtime.windowExpiresAt())
            .groupSharedMaxRunningJobs(queue.groupSharedMaxRunningJobs())
            .activeJobs(activeJobs)
            .createdPartitions(backlog.createdPartitions())
            .waitingPartitions(backlog.waitingPartitions())
            .readyPartitions(backlog.readyPartitions())
            .runningPartitions(backlog.runningPartitions())
            .retryingPartitions(backlog.retryingPartitions())
            .queuedPartitions(backlog.queuedPartitions())
            .activePartitions(backlog.activePartitions())
            .oldestWaitingSeconds(backlog.oldestWaitingSeconds())
            .tenantWaitingSharePermille(waitingSharePermille)
            .partitionSaturationPermille(activePartitionPermille)
            .bottleneckReason(queueBottleneckReason)
            .build();
    return queueSnapshot;
  }

  private static QueuePartitionBacklogStats emptyBacklog(ResourceQueueEntity queue) {
    return new QueuePartitionBacklogStats(queue.queueCode(), 0, 0, 0, 0, 0, 0);
  }

  private static int permille(long numerator, long denominator) {
    if (numerator <= 0 || denominator <= 0) {
      return 0;
    }
    long scaled = numerator * 1000L / denominator;
    long nonNegativePermille = Math.max(0L, scaled);
    long cappedPermille = Math.min(1000L, nonNegativePermille);
    return (int) cappedPermille;
  }

  private static String bottleneckReason(
      ResourceQueueEntity queue,
      long activeJobs,
      int effectiveJobCap,
      QueuePartitionBacklogStats backlog,
      Set<String> onlineWorkerGroups) {
    if (backlog.waitingPartitions() <= 0 && backlog.createdPartitions() <= 0) {
      return BOTTLENECK_NONE;
    }
    int partitionCap = queue.maxRunningPartitions() == null ? 0 : queue.maxRunningPartitions();
    if (partitionCap > 0 && backlog.activePartitions() >= partitionCap) {
      return BOTTLENECK_QUEUE_PARTITION_LIMIT;
    }
    if (effectiveJobCap > 0 && activeJobs >= effectiveJobCap) {
      return BOTTLENECK_QUEUE_JOB_LIMIT;
    }
    if (Texts.hasText(queue.workerGroup())) {
      String normalizedWorkerGroup = queue.workerGroup().toLowerCase(Locale.ROOT);
      if (!onlineWorkerGroups.contains(normalizedWorkerGroup)) {
        return BOTTLENECK_NO_ONLINE_WORKER;
      }
    }
    return BOTTLENECK_WAITING_DISPATCH_BACKLOG;
  }

  private static Set<String> onlineWorkerGroups(
      List<SchedulerSnapshotResponse.WorkerLoadSnapshot> workers) {
    Set<String> groups = new HashSet<>();
    for (SchedulerSnapshotResponse.WorkerLoadSnapshot worker : workers) {
      if (Texts.hasText(worker.workerGroup())) {
        groups.add(worker.workerGroup().toLowerCase(Locale.ROOT));
      }
    }
    return groups;
  }

  private List<SchedulerSnapshotResponse.WorkerLoadSnapshot> buildInstanceSnapshot(
      String tenantId) {
    List<WorkerRegistryEntity> workers =
        workerRegistryMapper.selectByTenantAndStatus(tenantId, WorkerRegistryStatus.ONLINE.code());
    List<SchedulerSnapshotResponse.WorkerLoadSnapshot> wl = new ArrayList<>();
    for (WorkerRegistryEntity w : workers) {
      wl.add(new SchedulerSnapshotResponse.WorkerLoadSnapshot(
          w.workerCode(), w.workerGroup(), w.currentLoad(), w.heartbeatAt(), w.status()));
    }
    return wl;
  }

  public List<TenantSchedulerSnapshotEntity> history(String tenantId, int limit) {
    int minimumLimit = Math.max(limit, 1);
    int boundedLimit = Math.min(minimumLimit, MAX_SNAPSHOT_LIMIT);
    return tenantSchedulerSnapshotMapper.listRecent(tenantId, boundedLimit);
  }
}
