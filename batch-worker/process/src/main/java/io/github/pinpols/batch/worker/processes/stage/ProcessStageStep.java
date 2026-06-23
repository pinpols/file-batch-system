package io.github.pinpols.batch.worker.processes.stage;

import io.github.pinpols.batch.worker.processes.domain.ProcessJobContext;
import io.github.pinpols.batch.worker.processes.domain.ProcessStage;
import io.github.pinpols.batch.worker.processes.domain.ProcessStageResult;

public interface ProcessStageStep {

  ProcessStage stage();

  default String stepCode() {
    return "PROCESS_" + stage().name();
  }

  default String stepName() {
    return stepCode();
  }

  default String implCode() {
    return stepCode();
  }

  ProcessStageResult execute(ProcessJobContext context);
}
