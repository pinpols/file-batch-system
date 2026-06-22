package com.example.batch.worker.processes.domain;

import com.example.batch.common.exception.BizException;
import com.example.batch.common.i18n.BizExceptionUtils;
import com.example.batch.common.i18n.LocalizedError;
import com.example.batch.worker.core.support.StageExecutionResult;
import com.fasterxml.jackson.databind.ObjectMapper;

public record ProcessStageResult(
    ProcessStage stage,
    boolean success,
    String code,
    String message,
    String errorKey,
    String errorArgs)
    implements StageExecutionResult {

  public ProcessStageResult(ProcessStage stage, boolean success, String code, String message) {
    this(stage, success, code, message, null, null);
  }

  public static ProcessStageResult success(ProcessStage stage) {
    return new ProcessStageResult(stage, true, "SUCCESS", "ok");
  }

  public static ProcessStageResult failure(ProcessStage stage, String code, String message) {
    return new ProcessStageResult(stage, false, code, message);
  }

  public static ProcessStageResult failure(
      ProcessStage stage, String code, BizException exception, ObjectMapper objectMapper) {
    LocalizedError error = BizExceptionUtils.toLocalizedError(exception, null, objectMapper);
    return new ProcessStageResult(
        stage, false, code, error.renderedMessage(), error.key(), error.argsJson());
  }

  public static ProcessStageResult failure(
      ProcessStage stage,
      String code,
      String errorKey,
      Object[] args,
      String renderedMessage,
      ObjectMapper objectMapper) {
    LocalizedError error = BizExceptionUtils.of(errorKey, args, renderedMessage, objectMapper);
    return new ProcessStageResult(
        stage, false, code, error.renderedMessage(), error.key(), error.argsJson());
  }
}
