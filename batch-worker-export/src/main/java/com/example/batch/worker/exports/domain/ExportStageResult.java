package com.example.batch.worker.exports.domain;

import com.example.batch.worker.core.support.StageExecutionResult;

public record ExportStageResult(ExportStage stage, boolean success, String code, String message)
    implements StageExecutionResult {

  public static ExportStageResult success(ExportStage stage) {
    return new ExportStageResult(stage, true, "SUCCESS", "ok");
  }

  public static ExportStageResult failure(ExportStage stage, String code, String message) {
    return new ExportStageResult(stage, false, code, message);
  }
}
