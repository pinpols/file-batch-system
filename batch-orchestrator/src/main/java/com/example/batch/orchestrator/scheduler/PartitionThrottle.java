package com.example.batch.orchestrator.scheduler;

import com.example.batch.orchestrator.domain.entity.ResourceQueueRecord;

public interface PartitionThrottle {

    ResourceCheck check(ResourceSchedulingRequest request, ResourceQueueRecord queue);
}
