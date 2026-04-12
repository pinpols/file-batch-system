package com.example.batch.orchestrator.application.scheduler;

import com.example.batch.orchestrator.domain.entity.ResourceQueueRecord;
import com.example.batch.orchestrator.domain.scheduler.ResourceCheck;
import com.example.batch.orchestrator.domain.scheduler.ResourceSchedulingRequest;

public interface ConcurrencyLimiter {

  ResourceCheck check(ResourceSchedulingRequest request, ResourceQueueRecord queue);
}
