package io.github.pinpols.batch.worker.exports.stage;

import io.github.pinpols.batch.worker.exports.domain.ExportJobContext;
import io.github.pinpols.batch.worker.exports.domain.ExportStage;
import io.github.pinpols.batch.worker.exports.domain.ExportStageResult;

public interface ExportStageStep {

  ExportStage stage();

  default String stepCode() {
    return "EXPORT_" + stage().name();
  }

  default String stepName() {
    return stepCode();
  }

  default String implCode() {
    return stepCode();
  }

  ExportStageResult execute(ExportJobContext context);
}
