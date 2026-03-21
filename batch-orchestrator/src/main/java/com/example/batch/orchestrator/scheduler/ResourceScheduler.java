package com.example.batch.orchestrator.scheduler;

public interface ResourceScheduler {

    ResourceSchedulingDecision schedule(ResourceSchedulingRequest request);
}
