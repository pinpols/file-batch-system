package io.github.pinpols.batch.worker.dispatchs.stage;

import io.github.pinpols.batch.worker.dispatchs.domain.DispatchJobContext;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchStage;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchStageResult;

public interface DispatchStageStep {

  DispatchStage stage();

  default String stepCode() {
    return "DISPATCH_" + stage().name();
  }

  default String stepName() {
    return stepCode();
  }

  default String implCode() {
    return stepCode();
  }

  DispatchStageResult execute(DispatchJobContext context);
}
