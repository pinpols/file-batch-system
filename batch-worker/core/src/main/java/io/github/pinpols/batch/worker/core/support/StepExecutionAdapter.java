package io.github.pinpols.batch.worker.core.support;

import io.github.pinpols.batch.worker.core.domain.StepExecutionRequest;
import io.github.pinpols.batch.worker.core.domain.StepExecutionResponse;

public interface StepExecutionAdapter {

  StepExecutionResponse execute(StepExecutionRequest request);
}
