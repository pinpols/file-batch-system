package io.github.pinpols.batch.worker.dispatchs.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.i18n.BizExceptionUtils;
import io.github.pinpols.batch.common.i18n.LocalizedError;
import io.github.pinpols.batch.worker.core.support.StageExecutionResult;

/** 分发阶段执行结果，包含阶段标识、成功标志及错误码/消息。 */
public record DispatchStageResult(
    DispatchStage stage,
    boolean success,
    String code,
    String message,
    String errorKey,
    String errorArgs)
    implements StageExecutionResult {

  public DispatchStageResult(DispatchStage stage, boolean success, String code, String message) {
    this(stage, success, code, message, null, null);
  }

  public static DispatchStageResult success(DispatchStage stage) {
    return new DispatchStageResult(stage, true, "SUCCESS", "ok");
  }

  public static DispatchStageResult failure(DispatchStage stage, String code, String message) {
    return new DispatchStageResult(stage, false, code, message);
  }

  public static DispatchStageResult failure(
      DispatchStage stage, String code, BizException exception, ObjectMapper objectMapper) {
    LocalizedError error = BizExceptionUtils.toLocalizedError(exception, null, objectMapper);
    return new DispatchStageResult(
        stage, false, code, error.renderedMessage(), error.key(), error.argsJson());
  }

  public static DispatchStageResult failure(
      DispatchStage stage,
      String code,
      String errorKey,
      Object[] args,
      String renderedMessage,
      ObjectMapper objectMapper) {
    LocalizedError error = BizExceptionUtils.of(errorKey, args, renderedMessage, objectMapper);
    return new DispatchStageResult(
        stage, false, code, error.renderedMessage(), error.key(), error.argsJson());
  }
}
