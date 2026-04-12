package com.example.batch.orchestrator.domain.pipeline;

import com.example.batch.common.model.WorkerRouteModel;

public interface Step {

  String stepCode();

  StepResult execute(ExecutionContext context, WorkerRouteModel workerRouteModel);
}
