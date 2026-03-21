package com.example.batch.orchestrator.scheduler;

import com.example.batch.orchestrator.domain.entity.ResourceQueueRecord;

public interface PriorityScheduler {

    Integer resolvePriority(ResourceSchedulingRequest request, ResourceQueueRecord queue);

    String resolvePriorityBand(Integer priority);
}
