package com.example.batch.orchestrator.application.plan;

public interface SchedulePlanBuilder {

    SchedulePlan build(SchedulePlanCommand command);
}
