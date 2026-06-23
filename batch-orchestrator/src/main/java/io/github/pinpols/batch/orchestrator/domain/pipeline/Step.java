package io.github.pinpols.batch.orchestrator.domain.pipeline;

import io.github.pinpols.batch.common.model.WorkerRouteModel;

/** 实现类的 {@code stepCode()} 必须等于 Spring Bean name，{@link StepRegistry} 据此查找。 */
public interface Step {

  String stepCode();

  StepResult execute(ExecutionContext context, WorkerRouteModel workerRouteModel);
}
