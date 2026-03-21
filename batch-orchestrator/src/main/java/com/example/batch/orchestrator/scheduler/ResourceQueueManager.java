package com.example.batch.orchestrator.scheduler;

import com.example.batch.orchestrator.domain.entity.ResourceQueueRecord;

public interface ResourceQueueManager {

    ResourceQueueRecord resolveQueue(ResourceSchedulingRequest request);
}
