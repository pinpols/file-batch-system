package com.example.batch.worker.imports.domain;

import com.example.batch.common.exception.BizException;
import com.example.batch.common.i18n.BizExceptionUtils;
import com.example.batch.common.i18n.LocalizedError;
import com.example.batch.worker.core.support.StageExecutionResult;
import com.fasterxml.jackson.databind.ObjectMapper;

public record ImportStageResult(
    ImportStage stage,
    boolean success,
    String code,
    String message,
    String errorKey,
    String errorArgs)
    implements StageExecutionResult {

  public ImportStageResult(ImportStage stage, boolean success, String code, String message) {
    this(stage, success, code, message, null, null);
  }

  public static ImportStageResult success(ImportStage stage) {
    return new ImportStageResult(stage, true, "SUCCESS", "ok");
  }

  public static ImportStageResult failure(ImportStage stage, String code, String message) {
    return new ImportStageResult(stage, false, code, message);
  }

  public static ImportStageResult failure(
      ImportStage stage, String code, BizException exception, ObjectMapper objectMapper) {
    LocalizedError error = BizExceptionUtils.toLocalizedError(exception, null, objectMapper);
    return new ImportStageResult(
        stage, false, code, error.renderedMessage(), error.key(), error.argsJson());
  }

  public static ImportStageResult failure(
      ImportStage stage,
      String code,
      String errorKey,
      Object[] args,
      String renderedMessage,
      ObjectMapper objectMapper) {
    LocalizedError error = BizExceptionUtils.of(errorKey, args, renderedMessage, objectMapper);
    return new ImportStageResult(
        stage, false, code, error.renderedMessage(), error.key(), error.argsJson());
  }
}
