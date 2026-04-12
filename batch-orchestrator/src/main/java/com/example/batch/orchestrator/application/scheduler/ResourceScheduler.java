package com.example.batch.orchestrator.application.scheduler;

import com.example.batch.orchestrator.domain.scheduler.ResourceSchedulingDecision;
import com.example.batch.orchestrator.domain.scheduler.ResourceSchedulingRequest;

public interface ResourceScheduler {

  ResourceSchedulingDecision schedule(ResourceSchedulingRequest request);
}
