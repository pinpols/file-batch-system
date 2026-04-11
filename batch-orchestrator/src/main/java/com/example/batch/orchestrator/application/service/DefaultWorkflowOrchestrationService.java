package com.example.batch.orchestrator.application.service;

import com.example.batch.orchestrator.application.engine.ScheduleForwarder;
import com.example.batch.orchestrator.application.plan.SchedulePlan;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DefaultWorkflowOrchestrationService implements WorkflowOrchestrationService {

    private final ScheduleForwarder scheduleForwarder;

    @Override
    public void submit(SchedulePlan plan) {
        scheduleForwarder.advance(plan);
    }
}
