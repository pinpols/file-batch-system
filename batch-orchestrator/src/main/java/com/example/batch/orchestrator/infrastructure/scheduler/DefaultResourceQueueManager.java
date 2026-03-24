package com.example.batch.orchestrator.infrastructure.scheduler;

import com.example.batch.orchestrator.application.scheduler.ResourceQueueManager;
import com.example.batch.orchestrator.domain.entity.ResourceQueueRecord;
import com.example.batch.orchestrator.domain.scheduler.ResourceSchedulingRequest;
import com.example.batch.orchestrator.repository.ResourceQueueRepository;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class DefaultResourceQueueManager implements ResourceQueueManager {

    private final ResourceQueueRepository resourceQueueRepository;

    @Override
    public ResourceQueueRecord resolveQueue(ResourceSchedulingRequest request) {
        if (request == null || !StringUtils.hasText(request.getTenantId())) {
            return null;
        }
        List<ResourceQueueRecord> queues = resourceQueueRepository.findByTenantIdAndEnabled(request.getTenantId(), true);
        if (queues == null || queues.isEmpty()) {
            return null;
        }
        if (StringUtils.hasText(request.getQueueCode())) {
            return queues.stream()
                    .filter(queue -> request.getQueueCode().equalsIgnoreCase(queue.queueCode()))
                    .findFirst()
                    .orElse(null);
        }
        return queues.stream()
                .filter(queue -> matchesQueueType(queue, request.getWorkerType()))
                .sorted(Comparator
                        .comparing((ResourceQueueRecord queue) -> !"MIXED".equalsIgnoreCase(queue.queueType()))
                        .thenComparing(queue -> normalizedWeight(queue.fairShareWeight()), Comparator.reverseOrder())
                        .thenComparing(queue -> normalizedWeight(queue.maxRunningJobs()), Comparator.reverseOrder())
                        .thenComparing(queue -> normalizedWeight(queue.maxRunningPartitions()), Comparator.reverseOrder())
                        .thenComparing(ResourceQueueRecord::queueCode, Comparator.nullsLast(String::compareToIgnoreCase)))
                .findFirst()
                .orElse(null);
    }

    private boolean matchesQueueType(ResourceQueueRecord queue, String workerType) {
        if (queue == null) {
            return false;
        }
        if (!StringUtils.hasText(workerType)) {
            return true;
        }
        return workerType.equalsIgnoreCase(queue.queueType())
                || "MIXED".equalsIgnoreCase(queue.queueType());
    }

    private Integer normalizedWeight(Integer weight) {
        return weight == null || weight <= 0 ? 1 : weight;
    }
}
