package com.example.batch.orchestrator.application.service;

import com.example.batch.orchestrator.application.engine.ScheduleForwarder;
import com.example.batch.orchestrator.application.plan.SchedulePlan;
import org.springframework.stereotype.Service;

@Service
public class DefaultWorkflowOrchestrationService implements WorkflowOrchestrationService {

    private final ScheduleForwarder scheduleForwarder;

    public DefaultWorkflowOrchestrationService(ScheduleForwarder scheduleForwarder) {
        this.scheduleForwarder = scheduleForwarder;
    }

    @Override
    public void submit(SchedulePlan plan) {
        scheduleForwarder.advance(plan);
    }
}
