package com.example.batch.worker.processes.domain;

import com.example.batch.worker.core.support.StageExecutionResult;

public record ProcessStageResult(ProcessStage stage, boolean success, String code, String message)
    implements StageExecutionResult {

  public static ProcessStageResult success(ProcessStage stage) {
    return new ProcessStageResult(stage, true, "SUCCESS", "ok");
  }

  public static ProcessStageResult failure(ProcessStage stage, String code, String message) {
    return new ProcessStageResult(stage, false, code, message);
  }
}
