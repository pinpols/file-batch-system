package com.example.batch.orchestrator.application.engine;

import com.example.batch.orchestrator.application.plan.SchedulePlan;

public interface ScheduleForwarder {
  ScheduleForwarderResult advance(SchedulePlan plan);
}
