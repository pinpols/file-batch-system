package com.example.batch.orchestrator.infrastructure.scheduler;

import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.common.model.WorkerRouteModel;
import com.example.batch.orchestrator.application.scheduler.WorkerSelector;
import com.example.batch.orchestrator.domain.entity.ResourceQueueRecord;
import com.example.batch.orchestrator.domain.entity.WorkerRegistryRecord;
import com.example.batch.orchestrator.domain.scheduler.ResourceSchedulingRequest;
import com.example.batch.orchestrator.repository.WorkerRegistryRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

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
@Component
@RequiredArgsConstructor
public class DefaultWorkerSelector implements WorkerSelector {

  private final WorkerRegistryRepository workerRegistryRepository;

  @Override
  public WorkerRouteModel select(
      ResourceSchedulingRequest request, ResourceQueueRecord queue, Integer priority) {
    WorkerRouteModel route = new WorkerRouteModel();
    route.setWorkerType(request == null ? null : request.getWorkerType());
    route.setPriority(priority);
    route.setResourceProfile(queue == null ? null : queue.resourceTag());
    if (request == null || !StringUtils.hasText(request.getTenantId())) {
      route.setAvailable(false);
      return route;
    }
    String workerGroup = resolveWorkerGroup(request, queue);
    List<WorkerRegistryRecord> candidates =
        StringUtils.hasText(workerGroup)
            ? workerRegistryRepository.findByTenantIdAndWorkerGroupAndStatus(
                request.getTenantId(), workerGroup, WorkerRegistryStatus.ONLINE.code())
            : workerRegistryRepository.findByTenantIdAndStatus(
                request.getTenantId(), WorkerRegistryStatus.ONLINE.code());
    WorkerRegistryRecord selected =
        candidates.stream()
            .filter(candidate -> matchesResourceTag(candidate, queue))
            .min(
                Comparator.comparingInt(
                        (WorkerRegistryRecord r) -> Optional.ofNullable(r.currentLoad()).orElse(0))
                    .thenComparing(
                        WorkerRegistryRecord::heartbeatAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
            .orElse(null);
    if (selected == null) {
      route.setAvailable(false);
      return route;
    }
    route.setWorkerCode(selected.workerCode());
    route.setAvailable(true);
    return route;
  }

  private boolean matchesResourceTag(WorkerRegistryRecord candidate, ResourceQueueRecord queue) {
    if (queue == null || !StringUtils.hasText(queue.resourceTag())) {
      return true;
    }
    return queue.resourceTag().equalsIgnoreCase(candidate.resourceTag());
  }

  private String resolveWorkerGroup(ResourceSchedulingRequest request, ResourceQueueRecord queue) {
    if (request != null && StringUtils.hasText(request.getWorkerGroup())) {
      return request.getWorkerGroup();
    }
    return queue == null ? null : queue.workerGroup();
  }
}
