package io.github.pinpols.batch.trigger.web;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.i18n.BizMessageResolver;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.web.AbstractApiExceptionHandler;
import io.github.pinpols.batch.trigger.infrastructure.TriggerSchedulerBusyException;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Trigger 模块全局异常处理器：继承 {@link AbstractApiExceptionHandler} 取得 Biz / System / 通用异常的统一处理；额外追加 Spring
 * Web 校验相关异常 （MethodArgumentNotValid / ConstraintViolation / MissingRequestHeader）的映射。
 */
@RestControllerAdvice
public class TriggerApiExceptionHandler extends AbstractApiExceptionHandler {

  public TriggerApiExceptionHandler(BizMessageResolver bizMessageResolver) {
    super(bizMessageResolver);
  }

  /** standalone MockMvc 测试用 — 无 Spring 容器,resolver=null 走父类降级路径返回 raw message。 */
  public static TriggerApiExceptionHandler forStandaloneTest() {
    return new TriggerApiExceptionHandler(null);
  }

  @Override
  protected String modulePrefix() {
    return "trigger";
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<CommonResponse<Void>> handleMethodArgumentNotValidException(
      MethodArgumentNotValidException exception) {
    String message = exception.getBindingResult().getFieldErrors().stream()
        .map(FieldError::getDefaultMessage)
        .collect(Collectors.joining("; "));
    if (EmptyChecks.isBlank(message)) {
      message = resolveCommonCode(ResultCode.VALIDATION_ERROR, ResultCode.VALIDATION_ERROR.label());
    }
    CommonResponse<Void> failureBody = CommonResponse.failure(ResultCode.VALIDATION_ERROR, message);
    return ResponseEntity.badRequest().body(failureBody);
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<CommonResponse<Void>> handleConstraintViolationException(
      ConstraintViolationException exception) {
    String message = exception.getMessage();
    if (EmptyChecks.isBlank(message)) {
      message = resolveCommonCode(ResultCode.VALIDATION_ERROR, ResultCode.VALIDATION_ERROR.label());
    }
    CommonResponse<Void> failureBody = CommonResponse.failure(ResultCode.VALIDATION_ERROR, message);
    return ResponseEntity.badRequest().body(failureBody);
  }

  @ExceptionHandler(MissingRequestHeaderException.class)
  public ResponseEntity<CommonResponse<Void>> handleMissingHeader(
      MissingRequestHeaderException exception) {
    ResultCode code =
        CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER.equalsIgnoreCase(exception.getHeaderName())
            ? ResultCode.MISSING_IDEMPOTENCY_KEY
            : ResultCode.INVALID_ARGUMENT;
    // 不再透出 Spring 默认英文 "Required request header 'X' for method parameter type ..."
    String message = resolveCommonCode(code, code.label());
    CommonResponse<Void> failureBody = CommonResponse.failure(code, message);
    return ResponseEntity.badRequest().body(failureBody);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<CommonResponse<Void>> handleUnreadableRequestBody(
      HttpMessageNotReadableException exception) {
    String message =
        resolveCommonCode(ResultCode.INVALID_ARGUMENT, ResultCode.INVALID_ARGUMENT.label());
    CommonResponse<Void> body = CommonResponse.failure(ResultCode.INVALID_ARGUMENT, message);
    return ResponseEntity.badRequest().body(body);
  }

  @ExceptionHandler(TriggerSchedulerBusyException.class)
  public ResponseEntity<CommonResponse<Void>> handleTriggerSchedulerBusy(
      TriggerSchedulerBusyException exception) {
    String message = resolveCommonCode(ResultCode.RATE_LIMITED, ResultCode.RATE_LIMITED.label());
    CommonResponse<Void> failureBody = CommonResponse.failure(ResultCode.RATE_LIMITED, message);
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(failureBody);
  }
}
