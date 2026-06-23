package io.github.pinpols.batch.worker.imports.stage;

import io.github.pinpols.batch.worker.imports.domain.ImportJobContext;
import io.github.pinpols.batch.worker.imports.domain.ImportStage;
import io.github.pinpols.batch.worker.imports.domain.ImportStageResult;

public interface ImportStageStep {

  ImportStage stage();

  default String stepCode() {
    return "IMPORT_" + stage().name();
  }

  default String stepName() {
    return stepCode();
  }

  default String implCode() {
    return stepCode();
  }

  ImportStageResult execute(ImportJobContext context);
}
