package com.example.batch.orchestrator.infrastructure.scheduler;

import com.example.batch.common.enums.PartitionStatus;
import com.example.batch.common.utils.Texts;
import com.example.batch.orchestrator.application.scheduler.PartitionThrottle;
import com.example.batch.orchestrator.application.scheduler.QuotaRuntimeStateService;
import com.example.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import com.example.batch.orchestrator.domain.entity.ResourceQueueEntity;
import com.example.batch.orchestrator.domain.entity.TenantQuotaPolicyEntity;
import com.example.batch.orchestrator.domain.param.CountActiveByGroupParam;
import com.example.batch.orchestrator.domain.scheduler.ResourceCheck;
import com.example.batch.orchestrator.domain.scheduler.ResourceSchedulingRequest;
import com.example.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 分区级闸门：按 job_partition 计数，与 {@link DefaultConcurrencyLimiter} 平行但维度不同 ——后者管 job instance 数，本类管
 * partition 数（一个 job 可能切成很多分区，分区数才真正决定消费压力）。
 *
 * <p>计数口径：活跃状态 = {@code WAITING / READY / RUNNING / RETRYING}（注意不包含 CREATED， 新建尚未进调度的分区不计入），与
 * {@link DefaultConcurrencyLimiter} 的 job-instance 活跃口径解耦。
 *
 * <p>两层闸门：
 *
 * <ul>
 *   <li><b>Tenant</b>：{@code maxPartitionsPerTenant} + {@code partitionBurstLimit} 软弹性，走 {@link
 *       QuotaRuntimeStateService}。
 *   <li><b>Queue</b>：{@code maxRunningPartitions} + {@code burstLimit}；若调度请求带 {@code workerGroup}
 *       则把计数细化到 {@code (tenant, workerGroup)} 组合——避免多 workerGroup 共享队列时互相踩数。
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class DefaultPartitionThrottle implements PartitionThrottle {

  private final JobPartitionMapper jobPartitionMapper;
  private final OrchestratorConfigCacheService configCacheService;
  private final QuotaRuntimeStateService quotaRuntimeStateService;
  private final BatchOrchestratorGovernanceProperties governance;

  @Override
  public ResourceCheck check(ResourceSchedulingRequest request, ResourceQueueEntity queue) {
    if (request == null || !Texts.hasText(request.getTenantId())) {
      return ResourceCheck.allow();
    }
    int requestedPartitions = Math.max(request.getRequestedPartitionCount(), 1);
    TenantQuotaPolicyEntity quotaPolicy = resolveQuotaPolicy(request.getTenantId());
    long tenantActivePartitions =
        jobPartitionMapper.countActiveByTenant(
            request.getTenantId(),
            PartitionStatus.WAITING.code(),
            PartitionStatus.READY.code(),
            PartitionStatus.RUNNING.code(),
            PartitionStatus.RETRYING.code());
    if (quotaPolicy != null
        && quotaPolicy.maxPartitionsPerTenant() != null
        && quotaPolicy.maxPartitionsPerTenant() > 0) {
      int pburst =
          quotaPolicy.partitionBurstLimit() == null
              ? 0
              : Math.max(0, quotaPolicy.partitionBurstLimit());
      ResourceCheck burstCheck =
          quotaRuntimeStateService.evaluateAndReserve(
              new QuotaRuntimeStateService.QuotaReservationRequest(
                  new QuotaRuntimeStateService.QuotaReservationOwner(
                      request.getTenantId(), "TENANT_PARTITIONS", request.getTenantId()),
                  new QuotaRuntimeStateService.QuotaReservationPolicy(
                      quotaPolicy.quotaResetPolicy(),
                      quotaPolicy.maxPartitionsPerTenant(),
                      pburst,
                      governance.resourceScheduler().getQuotaResetSlidingWindowHours()),
                  tenantActivePartitions,
                  requestedPartitions,
                  new QuotaRuntimeStateService.QuotaReservationReason(
                      "TENANT_PARTITION_LIMIT",
                      "tenant running partitions exceed quota (including" + " partition burst)")));
      if (!burstCheck.allowed()) {
        return burstCheck;
      }
    }
    if (queue != null && queue.maxRunningPartitions() != null && queue.maxRunningPartitions() > 0) {
      long queueActivePartitions =
          countQueueActivePartitions(request, queue, tenantActivePartitions);
      int burst = queue.burstLimit() == null ? 0 : Math.max(0, queue.burstLimit());
      ResourceCheck burstCheck =
          quotaRuntimeStateService.evaluateAndReserve(
              new QuotaRuntimeStateService.QuotaReservationRequest(
                  new QuotaRuntimeStateService.QuotaReservationOwner(
                      request.getTenantId(), "QUEUE_PARTITIONS", queue.queueCode()),
                  new QuotaRuntimeStateService.QuotaReservationPolicy(
                      queue.quotaResetPolicy(),
                      queue.maxRunningPartitions(),
                      burst,
                      governance.resourceScheduler().getQuotaResetSlidingWindowHours()),
                  queueActivePartitions,
                  requestedPartitions,
                  new QuotaRuntimeStateService.QuotaReservationReason(
                      "QUEUE_PARTITION_LIMIT", "resource queue running partitions exceed limit")));
      if (!burstCheck.allowed()) {
        return burstCheck;
      }
    }
    return ResourceCheck.allow();
  }

  private long countQueueActivePartitions(
      ResourceSchedulingRequest request, ResourceQueueEntity queue, long tenantActivePartitions) {
    String workerGroup =
        Texts.hasText(request.getWorkerGroup()) ? request.getWorkerGroup() : queue.workerGroup();
    if (!Texts.hasText(workerGroup)) {
      return tenantActivePartitions;
    }
    return jobPartitionMapper.countActiveByTenantAndWorkerGroup(
        CountActiveByGroupParam.builder()
            .tenantId(request.getTenantId())
            .workerGroup(workerGroup)
            .waitingStatus(PartitionStatus.WAITING.code())
            .readyStatus(PartitionStatus.READY.code())
            .runningStatus(PartitionStatus.RUNNING.code())
            .retryingStatus(PartitionStatus.RETRYING.code())
            .build());
  }

  private TenantQuotaPolicyEntity resolveQuotaPolicy(String tenantId) {
    return configCacheService.findEnabledQuotaPolicy(tenantId);
  }
}
