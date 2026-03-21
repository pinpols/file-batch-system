package com.example.batch.orchestrator.scheduler;

import com.example.batch.orchestrator.domain.entity.ResourceQueueRecord;
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
                    .filter(queue -> request.getQueueCode().equalsIgnoreCase(queue.getQueueCode()))
                    .findFirst()
                    .orElse(null);
        }
        return queues.stream()
                .filter(queue -> matchesQueueType(queue, request.getWorkerType()))
                .sorted(Comparator.comparing(queue -> !"MIXED".equalsIgnoreCase(queue.getQueueType())))
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
        return workerType.equalsIgnoreCase(queue.getQueueType())
                || "MIXED".equalsIgnoreCase(queue.getQueueType());
    }
}
