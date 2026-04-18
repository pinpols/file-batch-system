package com.example.batch.orchestrator.domain.pipeline;

import com.example.batch.common.model.WorkerRouteModel;

/** 实现类的 {@code stepCode()} 必须等于 Spring Bean name，{@link StepRegistry} 据此查找。 */
public interface Step {

  String stepCode();

  StepResult execute(ExecutionContext context, WorkerRouteModel workerRouteModel);
}
