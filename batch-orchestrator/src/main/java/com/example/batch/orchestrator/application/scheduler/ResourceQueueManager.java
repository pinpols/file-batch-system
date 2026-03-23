package com.example.batch.orchestrator.application.scheduler;

import com.example.batch.orchestrator.domain.entity.ResourceQueueRecord;
import com.example.batch.orchestrator.domain.scheduler.ResourceSchedulingRequest;

public interface ResourceQueueManager {

    ResourceQueueRecord resolveQueue(ResourceSchedulingRequest request);
}
