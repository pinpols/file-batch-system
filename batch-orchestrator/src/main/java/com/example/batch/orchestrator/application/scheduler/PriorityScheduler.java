package com.example.batch.orchestrator.application.scheduler;

import com.example.batch.orchestrator.domain.entity.ResourceQueueRecord;
import com.example.batch.orchestrator.domain.scheduler.ResourceSchedulingRequest;

public interface PriorityScheduler {

  Integer resolvePriority(ResourceSchedulingRequest request, ResourceQueueRecord queue);

  String resolvePriorityBand(Integer priority);
}
