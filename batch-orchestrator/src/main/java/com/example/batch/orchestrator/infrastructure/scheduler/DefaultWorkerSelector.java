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
