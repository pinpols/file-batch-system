package com.example.batch.orchestrator.application.service;

import com.example.batch.orchestrator.application.plan.SchedulePlan;

public interface WorkflowOrchestrationService {

    void submit(SchedulePlan plan);
}
