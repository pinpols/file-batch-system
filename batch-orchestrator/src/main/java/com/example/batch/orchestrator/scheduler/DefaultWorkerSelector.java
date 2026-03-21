package com.example.batch.orchestrator.scheduler;

import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.common.model.WorkerRouteModel;
import com.example.batch.orchestrator.domain.entity.ResourceQueueRecord;
import com.example.batch.orchestrator.domain.entity.WorkerRegistryRecord;
import com.example.batch.orchestrator.repository.WorkerRegistryRepository;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class DefaultWorkerSelector implements WorkerSelector {

    private final WorkerRegistryRepository workerRegistryRepository;

    @Override
    public WorkerRouteModel select(ResourceSchedulingRequest request, ResourceQueueRecord queue, Integer priority) {
        WorkerRouteModel route = new WorkerRouteModel();
        route.setWorkerType(request == null ? null : request.getWorkerType());
        route.setPriority(priority);
        route.setResourceProfile(queue == null ? null : queue.getResourceTag());
        if (request == null || !StringUtils.hasText(request.getTenantId())) {
            route.setAvailable(false);
            return route;
        }
        String workerGroup = resolveWorkerGroup(request, queue);
        List<WorkerRegistryRecord> candidates = StringUtils.hasText(workerGroup)
                ? workerRegistryRepository.findByTenantIdAndWorkerGroupAndStatus(
                request.getTenantId(),
                workerGroup,
                WorkerRegistryStatus.ONLINE.code()
        )
                : workerRegistryRepository.findByTenantIdAndStatus(
                request.getTenantId(),
                WorkerRegistryStatus.ONLINE.code()
        );
        WorkerRegistryRecord selected = candidates.stream()
                .filter(candidate -> matchesResourceTag(candidate, queue))
                .max(Comparator.comparing(WorkerRegistryRecord::getHeartbeatAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(null);
        if (selected == null) {
            route.setAvailable(false);
            return route;
        }
        route.setWorkerId(selected.getWorkerCode());
        route.setAvailable(true);
        return route;
    }

    private boolean matchesResourceTag(WorkerRegistryRecord candidate, ResourceQueueRecord queue) {
        if (queue == null || !StringUtils.hasText(queue.getResourceTag())) {
            return true;
        }
        return queue.getResourceTag().equalsIgnoreCase(candidate.getResourceTag());
    }

    private String resolveWorkerGroup(ResourceSchedulingRequest request, ResourceQueueRecord queue) {
        if (request != null && StringUtils.hasText(request.getWorkerGroup())) {
            return request.getWorkerGroup();
        }
        return queue == null ? null : queue.getWorkerGroup();
    }
}
