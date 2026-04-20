package com.example.batch.orchestrator.infrastructure.scheduler;

import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.common.model.WorkerRouteModel;
import com.example.batch.orchestrator.application.scheduler.WorkerSelector;
import com.example.batch.orchestrator.config.ResourceSchedulerProperties;
import com.example.batch.orchestrator.domain.entity.ResourceQueueRecord;
import com.example.batch.orchestrator.domain.entity.WorkerRegistryRecord;
import com.example.batch.orchestrator.domain.scheduler.ResourceSchedulingRequest;
import com.example.batch.orchestrator.repository.WorkerRegistryRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import com.example.batch.common.utils.CodeNormalizer;
import com.example.batch.common.utils.Texts;

/**
 * Worker 路由选择：从 ONLINE worker 中挑一个承接任务。
 *
 * <p>筛选链：
 *
 * <ol>
 *   <li>按 {@code (tenantId, workerGroup, status=ONLINE)} 过滤——workerGroup 缺省时退化为仅按租户 + ONLINE 过滤。
 *       <b>只选 ONLINE</b>：DRAINING / DECOMMISSIONED 状态的 worker 即使 heartbeat 仍在也不选
 *       （与 {@link com.example.batch.orchestrator.service.DefaultWorkerRegistryService} 的"状态不回退"不变量呼应）。
 *   <li>按 {@code resourceTag} 匹配队列要求（队列无标签则全通过）。
 *   <li>排序 {@code (currentLoad asc, heartbeatAt desc)}：当前负载最小优先，并列时心跳最新者优先——
 *       同时兼顾负载均衡与活跃度（最近心跳的 worker 状态最可信）。
 * </ol>
 *
 * <p>找不到匹配 worker 时返回 {@code available=false} 的 route（不返回 null），让调用方拿到 reasonCode。
 */
@Slf4j
@Component
public class DefaultWorkerSelector implements WorkerSelector {

  // A-3.2 a: 指标名对齐 batch.scheduler.* 前缀，和现有 scheduler 指标共面板
  private static final String METRIC_NO_MATCH = "batch.scheduler.worker_selection.no_match";

  private final WorkerRegistryRepository workerRegistryRepository;
  private final ObjectProvider<MeterRegistry> meterRegistryProvider;
  private final ResourceSchedulerProperties resourceSchedulerProperties;

  public DefaultWorkerSelector(
      WorkerRegistryRepository workerRegistryRepository,
      ObjectProvider<MeterRegistry> meterRegistryProvider,
      ResourceSchedulerProperties resourceSchedulerProperties) {
    this.workerRegistryRepository = workerRegistryRepository;
    this.meterRegistryProvider = meterRegistryProvider;
    this.resourceSchedulerProperties = resourceSchedulerProperties;
  }

  @Override
  public WorkerRouteModel select(
      ResourceSchedulingRequest request, ResourceQueueRecord queue, Integer priority) {
    WorkerRouteModel route = new WorkerRouteModel();
    route.setWorkerType(request == null ? null : request.getWorkerType());
    route.setPriority(priority);
    route.setResourceProfile(queue == null ? null : queue.resourceTag());
    if (request == null || !Texts.hasText(request.getTenantId())) {
      route.setAvailable(false);
      return route;
    }
    // 历史数据可能存在 IMPORT / import 大小写不一致；入口统一按大写比较兜底，
    // 长期由 V64__normalize_code_conventions.sql 把 DB 存量归一，之后这里 toUpper 就是纯防御性动作。
    String workerGroup = CodeNormalizer.toUpperOrNull(resolveWorkerGroup(request, queue));
    List<WorkerRegistryRecord> candidates =
        findCandidates(request.getTenantId(), workerGroup);
    WorkerRegistryRecord selected = pickBest(candidates, queue);

    // 共享 worker 池 fallback（仅本地联调 / 共享 dev 环境）：主租户查不到 ONLINE worker 时，
    // 按 batch.resource-scheduler.shared-tenant-fallback 配置的租户再查一次。
    // 生产 profile 不应设置此配置，以保留 CLAUDE.md §多租户隔离 的原严格语义。
    String fallbackTenant = resourceSchedulerProperties.getSharedTenantFallback();
    if (selected == null
        && Texts.hasText(fallbackTenant)
        && !fallbackTenant.equals(request.getTenantId())) {
      List<WorkerRegistryRecord> fallbackCandidates = findCandidates(fallbackTenant, workerGroup);
      WorkerRegistryRecord fallbackSelected = pickBest(fallbackCandidates, queue);
      if (fallbackSelected != null) {
        log.info(
            "worker selection fell back to shared tenant: tenantId={}, fallbackTenant={},"
                + " workerGroup={}, workerCode={}",
            request.getTenantId(),
            fallbackTenant,
            workerGroup,
            fallbackSelected.workerCode());
        route.setWorkerCode(fallbackSelected.workerCode());
        route.setAvailable(true);
        return route;
      }
    }

    if (selected == null) {
      // A-3.2 a: 空集不再静默阻塞——记 WARN + 计数，让 Grafana / 告警能发现：
      //   - candidates.isEmpty 说明整组 worker 下线（或 workerGroup 配错）
      //   - resourceTag 全不匹配说明 queue 配置与 worker 实际 tag 失配
      // 保留阻塞语义以确保任务不会跑到错误环境（安全优先，见 v3 A-3.2）。
      String reason =
          candidates.isEmpty()
              ? "no_online_workers_in_group"
              : "no_worker_matches_resource_tag";
      log.warn(
          "worker selection returned no match: tenantId={}, workerGroup={}, resourceTag={},"
              + " candidates={}, reason={} — task will block in WAITING until operator"
              + " intervenes",
          request.getTenantId(),
          workerGroup,
          queue == null ? null : queue.resourceTag(),
          candidates.size(),
          reason);
      incrementNoMatchCounter(request, queue, reason);
      route.setAvailable(false);
      return route;
    }
    route.setWorkerCode(selected.workerCode());
    route.setAvailable(true);
    return route;
  }

  private List<WorkerRegistryRecord> findCandidates(String tenantId, String workerGroup) {
    return Texts.hasText(workerGroup)
        ? workerRegistryRepository.findByTenantIdAndWorkerGroupAndStatus(
            tenantId, workerGroup, WorkerRegistryStatus.ONLINE.code())
        : workerRegistryRepository.findByTenantIdAndStatus(
            tenantId, WorkerRegistryStatus.ONLINE.code());
  }

  private WorkerRegistryRecord pickBest(
      List<WorkerRegistryRecord> candidates, ResourceQueueRecord queue) {
    return candidates.stream()
        .filter(candidate -> matchesResourceTag(candidate, queue))
        .min(
            Comparator.comparingInt(
                    (WorkerRegistryRecord r) -> Optional.ofNullable(r.currentLoad()).orElse(0))
                .thenComparing(
                    WorkerRegistryRecord::heartbeatAt,
                    Comparator.nullsLast(Comparator.reverseOrder())))
        .orElse(null);
  }

  private void incrementNoMatchCounter(
      ResourceSchedulingRequest request, ResourceQueueRecord queue, String reason) {
    MeterRegistry registry = meterRegistryProvider.getIfAvailable();
    if (registry == null) {
      return;
    }
    Counter.builder(METRIC_NO_MATCH)
        .tags(
            Tags.of(
                "tenantId", String.valueOf(request.getTenantId()),
                "workerType", String.valueOf(request.getWorkerType()),
                "resourceTag",
                    queue == null || queue.resourceTag() == null ? "none" : queue.resourceTag(),
                "reason", reason))
        .register(registry)
        .increment();
  }

  private boolean matchesResourceTag(WorkerRegistryRecord candidate, ResourceQueueRecord queue) {
    if (queue == null || !Texts.hasText(queue.resourceTag())) {
      return true;
    }
    return queue.resourceTag().equalsIgnoreCase(candidate.resourceTag());
  }

  private String resolveWorkerGroup(ResourceSchedulingRequest request, ResourceQueueRecord queue) {
    if (request != null && Texts.hasText(request.getWorkerGroup())) {
      return request.getWorkerGroup();
    }
    return queue == null ? null : queue.workerGroup();
  }
}
